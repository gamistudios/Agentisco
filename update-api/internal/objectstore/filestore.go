package objectstore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// FileStore is the local filesystem implementation of Store.
//
// Objects live under Root; downloads stream into a sibling temporary file that
// is renamed into place, so a crash or a cancelled transfer leaves no
// half-written APK reachable through a key.
type FileStore struct {
	Root string
}

var _ Store = (*FileStore)(nil)

// New prepares the root directory.
func New(root string) (*FileStore, error) {
	if strings.TrimSpace(root) == "" {
		return nil, errors.New("objectstore: root is required")
	}
	abs, err := filepath.Abs(root)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(abs, 0o755); err != nil {
		return nil, fmt.Errorf("objectstore: create root: %w", err)
	}
	return &FileStore{Root: abs}, nil
}

// tempMarker identifies files written by an in-flight Put.
const tempMarker = ".tmp-"

// resolve maps an object key to a path inside the root, rejecting traversal.
func (s *FileStore) resolve(key string) (string, error) {
	if key == "" {
		return "", fmt.Errorf("objectstore: empty key")
	}
	// Checked on the raw key: on Windows ToSlash would silently turn a backslash
	// into a separator and make the two platforms disagree about what is legal.
	if strings.HasPrefix(key, "/") || strings.Contains(key, "\\") {
		return "", fmt.Errorf("objectstore: invalid key %q", key)
	}
	cleaned := filepath.ToSlash(key)
	if strings.HasPrefix(cleaned, "/") {
		return "", fmt.Errorf("objectstore: invalid key %q", key)
	}
	for _, segment := range strings.Split(cleaned, "/") {
		if segment == "" || segment == "." || segment == ".." {
			return "", fmt.Errorf("objectstore: invalid key %q", key)
		}
		if !isSafeSegment(segment) {
			return "", fmt.Errorf("objectstore: invalid key %q", key)
		}
	}
	path := filepath.Join(s.Root, filepath.FromSlash(cleaned))
	rel, err := filepath.Rel(s.Root, path)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return "", fmt.Errorf("objectstore: invalid key %q", key)
	}
	return path, nil
}

func isSafeSegment(segment string) bool {
	for _, r := range segment {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9':
		case r == '.', r == '-', r == '_':
		default:
			return false
		}
	}
	return true
}

// Put writes body to key atomically and returns the stored object's info.
func (s *FileStore) Put(ctx context.Context, key string, body io.Reader) (Info, error) {
	dest, err := s.resolve(key)
	if err != nil {
		return Info{}, err
	}
	if err := ctx.Err(); err != nil {
		return Info{}, err
	}
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return Info{}, fmt.Errorf("objectstore: create dir: %w", err)
	}

	tmp, err := os.CreateTemp(filepath.Dir(dest), filepath.Base(dest)+tempMarker+"*")
	if err != nil {
		return Info{}, fmt.Errorf("objectstore: create temp: %w", err)
	}
	tmpName := tmp.Name()
	committed := false
	defer func() {
		if !committed {
			tmp.Close()
			os.Remove(tmpName)
		}
	}()

	// A cancelled or truncated transfer must never reach the final name, so the
	// copy is context-aware and the byte count is what we report.
	copied, err := copyCtx(ctx, tmp, body)
	if err != nil {
		return Info{}, err
	}
	if err := tmp.Sync(); err != nil {
		return Info{}, fmt.Errorf("objectstore: sync: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return Info{}, fmt.Errorf("objectstore: close: %w", err)
	}
	if err := os.Chmod(tmpName, 0o644); err != nil {
		return Info{}, fmt.Errorf("objectstore: chmod: %w", err)
	}
	if err := replace(dest, tmpName); err != nil {
		return Info{}, fmt.Errorf("objectstore: commit: %w", err)
	}
	committed = true

	info, err := s.Stat(ctx, key)
	if err != nil {
		return Info{}, err
	}
	if info.Size != copied {
		return Info{}, fmt.Errorf("objectstore: size changed while committing %q", key)
	}
	return info, nil
}

// replace moves tmp onto dest, tolerating a pre-existing object (Windows
// refuses to rename over an existing file).
func replace(dest, tmp string) error {
	err := os.Rename(tmp, dest)
	if err == nil {
		return nil
	}
	if !errors.Is(err, os.ErrExist) {
		return err
	}
	if removeErr := os.Remove(dest); removeErr != nil && !errors.Is(removeErr, os.ErrNotExist) {
		return removeErr
	}
	return os.Rename(tmp, dest)
}

// Open returns a seekable reader over the stored object.
func (s *FileStore) Open(ctx context.Context, key string) (Object, Info, error) {
	if err := ctx.Err(); err != nil {
		return nil, Info{}, err
	}
	path, err := s.resolve(key)
	if err != nil {
		return nil, Info{}, err
	}
	file, err := os.Open(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, Info{}, fmt.Errorf("%w: %s", ErrNotFound, key)
		}
		return nil, Info{}, fmt.Errorf("objectstore: open: %w", err)
	}
	stat, err := file.Stat()
	if err != nil {
		file.Close()
		return nil, Info{}, fmt.Errorf("objectstore: stat: %w", err)
	}
	return file, Info{Size: stat.Size(), ModTime: stat.ModTime()}, nil
}

// Stat reports the object's size and modification time.
func (s *FileStore) Stat(ctx context.Context, key string) (Info, error) {
	if err := ctx.Err(); err != nil {
		return Info{}, err
	}
	path, err := s.resolve(key)
	if err != nil {
		return Info{}, err
	}
	stat, err := os.Stat(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return Info{}, fmt.Errorf("%w: %s", ErrNotFound, key)
		}
		return Info{}, fmt.Errorf("objectstore: stat: %w", err)
	}
	if stat.IsDir() {
		return Info{}, fmt.Errorf("%w: %s", ErrNotFound, key)
	}
	return Info{Size: stat.Size(), ModTime: stat.ModTime()}, nil
}

// Delete removes an object; a missing object is not an error.
func (s *FileStore) Delete(ctx context.Context, key string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	path, err := s.resolve(key)
	if err != nil {
		return err
	}
	if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("objectstore: delete: %w", err)
	}
	return nil
}

// Move publishes a staged object under its final key, replacing any object
// already there.
func (s *FileStore) Move(ctx context.Context, from, to string) (Info, error) {
	source, err := s.resolve(from)
	if err != nil {
		return Info{}, err
	}
	dest, err := s.resolve(to)
	if err != nil {
		return Info{}, err
	}
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return Info{}, fmt.Errorf("objectstore: create dir: %w", err)
	}
	if err := replace(dest, source); err != nil {
		return Info{}, fmt.Errorf("objectstore: move: %w", err)
	}
	return s.Stat(ctx, to)
}

// CleanupTemp removes temporary files left by a process that died mid-download.
// Only files older than maxAge are touched, so a concurrent Put is never
// racing with this sweep.
func (s *FileStore) CleanupTemp(ctx context.Context, maxAge time.Duration) (int, error) {
	removed := 0
	err := filepath.WalkDir(s.Root, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return nil // unreadable corners of the cache must not abort the sweep
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		if entry.IsDir() || !strings.Contains(entry.Name(), tempMarker) {
			return nil
		}
		info, statErr := entry.Info()
		if statErr != nil || time.Since(info.ModTime()) < maxAge {
			return nil
		}
		if removeErr := os.Remove(path); removeErr == nil {
			removed++
		}
		return nil
	})
	return removed, err
}

func copyCtx(ctx context.Context, dst *os.File, src io.Reader) (int64, error) {
	buffer := make([]byte, 128*1024)
	var written int64
	for {
		if err := ctx.Err(); err != nil {
			return written, err
		}
		read, readErr := src.Read(buffer)
		if read > 0 {
			n, writeErr := dst.Write(buffer[:read])
			written += int64(n)
			if writeErr != nil {
				return written, writeErr
			}
		}
		if readErr == io.EOF {
			return written, nil
		}
		if readErr != nil {
			return written, readErr
		}
	}
}
