// JNI bridge to llama.cpp's chat-template layer.
//
// This is the part of the engine Awaki must not reimplement. Every GGUF file
// publishes its own chat template, and a custom model from an arbitrary URL carries
// whichever dialect its author chose: ChatML, Mistral, Llama-3, Gemma, a tool call
// inside a fenced JSON block, thinking in think tags. llama.cpp ships the renderer
// for all of them, plus a parser generated from the same template to read the model's
// answer back. Reusing that code is the difference between supporting one model and
// supporting the format.
//
// So a turn is a native object: opening it applies the template and builds the parser,
// reading it yields the prompt, grammar and stop sequences to decode with, parsing it
// turns generated text into an assistant message, closing it releases both. Kotlin
// never has to keep a grammar or a template in sync with anything.
//
// Everything crossing the boundary is UTF-8 byte arrays, for the reason in
// engine_shared.h.

#include "engine_shared.h"
#include <android/log.h>

#include <chat.h>
#include <json.h>

#include <algorithm>
#include <utility>
#include <vector>

namespace {

std::string string_field(const common_json &obj, const char *key, const std::string &fallback = std::string()) {
  if (!obj.contains(key)) return fallback;
  const common_json &value = obj.at(key);
  if (value.is_string()) return value.get<std::string>();
  if (value.is_number()) return value.dump();
  return fallback;
}

bool bool_field(const common_json &obj, const char *key, bool fallback) {
  return obj.contains(key) && obj.at(key).is_boolean() ? obj.at(key).get<bool>() : fallback;
}

/// A JSON array of OpenAI-shaped objects, or an empty array when it is absent.
common_json array_field(const common_json &obj, const char *key) {
  if (!obj.contains(key)) return common_json::array();
  const common_json &value = obj.at(key);
  return value.is_array() ? value : common_json::array();
}

void json_string_field(const common_json &obj, const char *key, std::string &out) {
  if (obj.contains(key) && obj.at(key).is_string()) out = obj.at(key).get<std::string>();
}

common_reasoning_format reasoning_format_of(const std::string &name) {
  return name.empty() ? COMMON_REASONING_FORMAT_DEEPSEEK : common_reasoning_format_from_name(name);
}

/// One request against one model: what its template produced, and the parser for it.
struct ChatTurn {
  common_chat_params params;
  common_chat_parser_params parser_params;

  /** The last parse, so each streamed piece only reports what changed. */
  common_chat_msg previous;
};

ChatTurn *turn_of(jlong handle) {
  return reinterpret_cast<ChatTurn *>(static_cast<intptr_t>(handle));
}

/**
 * Points the session at the turn its next decode will answer for.
 *
 * A turn's demands do not fit in the grammar text that reaches `nativeComplete`, and two of them
 * decide what the decode loop does with every token:
 *
 * - A preserved token arrives as text, less-than signs and all, and only the vocabulary can say
 *   whether it is one token at all. Detokenizing a special token yields nothing unless that token
 *   is named, so a model that marks its tool calls with markers had the markers deleted before
 *   either parser could see them.
 * - A trigger of type word is worth converting to a token when it tokenizes to one, because
 *   matching a token is free where matching a pattern runs a regex over the vocabulary. That is
 *   llama-server's hop too, and a word that becomes a token has to be preserved as well, since a
 *   marker cannot be both invisible to the decoder and the thing the decoder watches for.
 *
 * One turn is open at a time by construction: the resident model is under a lock for the whole of a
 * request, so recording the turn's demands on the session cannot race a decode that is not its own.
 */
void describe_turn_to(Session *s, const common_chat_params &p) {
  std::vector<llama_token> preserved;
  for (const auto &text : p.preserved_tokens) {
    const std::vector<llama_token> ids = common_tokenize(s->vocab, text, false, true);
    if (ids.size() == 1) preserved.push_back(ids[0]);
  }

  std::vector<common_grammar_trigger> triggers;
  for (const auto &trigger : p.grammar_triggers) {
    if (trigger.type != COMMON_GRAMMAR_TRIGGER_TYPE_WORD) {
      triggers.push_back(trigger);
      continue;
    }
    const std::vector<llama_token> ids = common_tokenize(s->vocab, trigger.value, false, true);
    if (ids.size() != 1) {
      triggers.push_back(trigger);
      continue;
    }
    if (std::find(preserved.begin(), preserved.end(), ids[0]) == preserved.end()) {
      preserved.push_back(ids[0]);
    }
    common_grammar_trigger as_token;
    as_token.type = COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN;
    as_token.value = trigger.value;
    as_token.token = ids[0];
    triggers.push_back(std::move(as_token));
  }

  s->preserved_tokens = std::move(preserved);
  s->grammar_lazy = p.grammar_lazy;
  s->grammar_triggers = std::move(triggers);
}

/// The template hands back a serialized PEG parser while the parser wants an arena.
/// This is the same hop llama-server does, so the answer read back matches the prompt
/// that was written.
common_chat_parser_params to_parser_params(const common_chat_params &cp, common_reasoning_format format, bool stream) {
  common_chat_parser_params pp(cp);
  pp.reasoning_format = format;
  pp.reasoning_in_content = stream && format == COMMON_REASONING_FORMAT_DEEPSEEK_LEGACY;
  pp.parse_tool_calls = true;
  if (!cp.parser.empty()) pp.parser.load(cp.parser);
  return pp;
}

std::string caps_json(const common_chat_templates *tmpls) {
  std::string out = "{";
  bool first = true;
  for (const auto &entry : common_chat_templates_get_caps(tmpls)) {
    if (!first) out += ",";
    first = false;
    out += "\"" + json_escape(entry.first) + "\":" + std::string(entry.second ? "true" : "false");
  }
  out += "}";
  return out;
}

std::string string_array_json(const std::vector<std::string> &values) {
  std::string out = "[";
  for (size_t i = 0; i < values.size(); i++) {
    if (i > 0) out += ",";
    out += common_json::make(values[i]).dump_safe();
  }
  out += "]";
  return out;
}

std::string tool_calls_json(const std::vector<common_chat_tool_call> &calls) {
  std::string out = "[";
  for (size_t i = 0; i < calls.size(); i++) {
    if (i > 0) out += ",";
    out += "{\"id\":" + common_json::make(calls[i].id).dump_safe();
    out += ",\"name\":" + common_json::make(calls[i].name).dump_safe();
    // Arguments stay a string here, exactly as the OpenAI wire format wants them:
    // during a partial parse they are a half-written object, and re-parsing them as
    // JSON would throw away the only thing the caller could still stream.
    out += ",\"arguments\":" + common_json::make(calls[i].arguments).dump_safe() + "}";
  }
  out += "]";
  return out;
}

/// The fields of one parsed assistant message. Written by hand rather than through
/// common_chat_msg::to_json_oaicompat, which parses tool arguments as JSON and so
/// throws on the partial messages a stream is made of.
std::string message_json(const common_chat_msg &msg) {
  std::string out = "{\"role\":\"" + json_escape(msg.role) + "\"";
  out += ",\"content\":" + common_json::make(msg.content).dump_safe();
  if (!msg.reasoning_content.empty()) {
    out += ",\"reasoning\":" + common_json::make(msg.reasoning_content).dump_safe();
  }
  out += ",\"toolCalls\":" + tool_calls_json(msg.tool_calls);
  out += "}";
  return out;
}

}  // namespace

extern "C" {

/**
 * What the resident model's own template can do, so tools, thinking and structured
 * output are offered only where the file behind them supports them. The catalog's
 * claim about a model is a wish; the template it ships is the fact.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_awaki_local_jni_NativeLlama_nativeChatTemplatesInfo(JNIEnv *env, jobject, jlong handle) {
  Session *s = session_of(handle);
  if (s == nullptr) return to_bytes(env, "{\"available\":false}", "the template's capabilities");
  if (s->templates == nullptr) {
    const std::string reason = g_last_error.empty() ? "The model ships no usable chat template" : g_last_error;
    return to_bytes(
        env,
        "{\"available\":false,\"reason\":" + common_json::make(reason).dump_safe() + "}",
        "the template's capabilities");
  }
  const common_chat_templates *tmpls = s->templates.get();
  std::string json = "{\"available\":true";
  json += ",\"explicit\":" + std::string(common_chat_templates_was_explicit(tmpls) ? "true" : "false");
  json += ",\"caps\":" + caps_json(tmpls);
  json += "}";
  return to_bytes(env, json, "the template's capabilities");
}

/**
 * Applies the model's template to one request and returns a turn: the rendered prompt,
 * the grammar that constrains tool calls, the sequences that end the turn, and the
 * parser that reads the answer back. Returns 0 when the request or the template was
 * rejected, in which case `nativeLastError` says which.
 */
JNIEXPORT jlong JNICALL
Java_com_awaki_local_jni_NativeLlama_nativeChatOpenTurn(JNIEnv *env, jobject, jlong handle, jbyteArray inputs) {
  Session *s = session_of(handle);
  if (s == nullptr) {
    set_error("No model is loaded");
    return 0;
  }
  if (s->templates == nullptr) {
    set_error(g_last_error.empty() ? "This model has no usable chat template" : g_last_error);
    return 0;
  }

  // An unreadable request is not the same as an empty one: the thread is already failing, the
  // reason is recorded, and a turn opened over text that was never read would be somebody else's.
  const std::optional<std::vector<char>> raw = copy_bytes(env, inputs);
  if (!raw.has_value()) return 0;
  const common_json body = common_json::parse_no_throw(std::string(raw->begin(), raw->end()));
  if (body.is_discarded() || !body.is_object()) {
    set_error("Chat request is not a JSON object");
    return 0;
  }

  try {
    // The Jinja renderer this call runs is compiled from whatever template the GGUF file shipped,
    // and it walks the whole transcript: the one step between tapping Send and the first token that
    // is neither decoding nor Kotlin.
    set_engine_phase("applying the chat template", nullptr);
    common_chat_templates_inputs in;
    in.messages = common_chat_msgs_parse_oaicompat(array_field(body, "messages"));
    in.tools = common_chat_tools_parse_oaicompat(array_field(body, "tools"));
    in.tool_choice = common_chat_tool_choice_parse_oaicompat(string_field(body, "tool_choice", "auto"));
    in.add_generation_prompt = bool_field(body, "add_generation_prompt", true);
    in.parallel_tool_calls = bool_field(body, "parallel_tool_calls", false);
    in.enable_thinking = bool_field(body, "enable_thinking", true);
    in.use_jinja = true;
    json_string_field(body, "grammar", in.grammar);
    json_string_field(body, "json_schema", in.json_schema);
    in.reasoning_format = reasoning_format_of(string_field(body, "reasoning_format"));

    ChatTurn *turn = new ChatTurn();
    turn->params = common_chat_templates_apply(s->templates.get(), in);
    turn->parser_params = to_parser_params(turn->params, in.reasoning_format, true);
    turn->previous.role = "assistant";
    describe_turn_to(s, turn->params);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(turn));
  } catch (const std::exception &e) {
    set_error(std::string("This model's chat template rejected the request: ") + e.what());
    return 0;
  }
}

/**
 * Everything the decode loop and the API layer need about a turn: the prompt to
 * evaluate, the grammar to sample under, and the sequences that end it. The stop list
 * is what makes a turn finish at the model's own end-of-turn marker instead of running
 * to max tokens, because that marker is a string in this template and a different one
 * in the next model's.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_awaki_local_jni_NativeLlama_nativeChatTurnInfo(JNIEnv *env, jobject, jlong turn_handle) {
  ChatTurn *turn = turn_of(turn_handle);
  if (turn == nullptr) return to_bytes(env, "{}", "the rendered prompt");
  const common_chat_params &p = turn->params;

  std::string json = "{";
  json += "\"prompt\":" + common_json::make(p.prompt).dump_safe();
  json += ",\"format\":\"" + json_escape(common_chat_format_name(p.format)) + "\"";
  json += ",\"grammar\":" + common_json::make(p.grammar).dump_safe();
  json += ",\"additionalStops\":" + string_array_json(p.additional_stops);
  json += ",\"supportsThinking\":" + std::string(p.supports_thinking ? "true" : "false");
  json += ",\"hasTools\":" + std::string(p.format != COMMON_CHAT_FORMAT_CONTENT_ONLY ? "true" : "false");
  json += "}";
  return to_bytes(env, json, "the rendered prompt");
}

/**
 * Reads generated text with the parser this turn's template produced. Returns the whole
 * assistant message plus the delta since the previous call, which is what lets a stream
 * carry tool arguments without ever showing the model's markup to the user.
 *
 * With partial=false the text is final; a model that answers in a shape its own template
 * does not allow then fails here rather than silently becoming prose.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_awaki_local_jni_NativeLlama_nativeChatParse(JNIEnv *env,
                                                        jobject,
                                                        jlong turn_handle,
                                                        jbyteArray text,
                                                        jboolean partial) {
  ChatTurn *turn = turn_of(turn_handle);
  if (turn == nullptr) return to_bytes(env, "{}", "the parsed answer");

  // Text the app could not hand over is not text that said nothing: the thread is already failing
  // and the reason is recorded, so there is no answer to read here.
  const std::optional<std::vector<char>> raw = copy_bytes(env, text);
  if (!raw.has_value()) return to_bytes(env, "{}", "the parsed answer");
  const std::string generated(raw->begin(), raw->end());

  set_engine_phase("parsing the answer", nullptr);
  try {
    const common_chat_msg msg = common_chat_parse(generated, partial == JNI_TRUE, turn->parser_params);
    std::string json = "{\"message\":" + message_json(msg);
    try {
      const std::vector<common_chat_msg_diff> diffs = common_chat_msg_diff::compute_diffs(turn->previous, msg);
      json += ",\"deltas\":[";
      for (size_t i = 0; i < diffs.size(); i++) {
        const common_chat_msg_diff &d = diffs[i];
        if (i > 0) json += ",";
        json += "{\"content\":" + common_json::make(d.content_delta).dump_safe();
        json += ",\"reasoning\":" + common_json::make(d.reasoning_content_delta).dump_safe();
        json += ",\"toolCallIndex\":" + std::to_string(d.tool_call_index == std::string::npos ? -1 : (long) d.tool_call_index);
        json += ",\"toolCall\":{\"id\":" + common_json::make(d.tool_call_delta.id).dump_safe();
        json += ",\"name\":" + common_json::make(d.tool_call_delta.name).dump_safe();
        json += ",\"arguments\":" + common_json::make(d.tool_call_delta.arguments).dump_safe() + "}}";
      }
      json += "]";
      turn->previous = msg;
    } catch (const std::exception &e) {
      // A diff that cannot be computed means the parser changed its mind about the
      // shape of the answer. The full message is still correct, so the caller gets it
      // and streams nothing for this piece instead of losing the turn.
      set_error(std::string("Streaming diff unavailable: ") + e.what());
      json += ",\"deltas\":[]";
      turn->previous = msg;
    }
    json += "}";
    return to_bytes(env, json, "the parsed answer");
  } catch (const std::exception &e) {
    set_error(std::string("The model produced output its own format does not allow: ") + e.what());
    // A partial parse failing is normal: the answer is not finished, so there is
    // simply nothing to report yet. Only a final failure is the model's fault, and
    // the caller needs to tell those apart before it shows anything to the user.
    std::string json = "{\"error\":true,\"partial\":" + std::string(partial == JNI_TRUE ? "true" : "false") + "}";
    return to_bytes(env, json, "the parsed answer");
  }
}

JNIEXPORT void JNICALL
Java_com_awaki_local_jni_NativeLlama_nativeChatCloseTurn(JNIEnv *, jobject, jlong turn_handle) {
  delete turn_of(turn_handle);
}

}  // extern "C"
