// Package objectstore keeps cached APK bytes. It is the seam that allows the
// local filesystem implementation to be replaced by S3/R2-compatible object
// storage without touching the synchronisation or HTTP layers.
package objectstore

import (
	"context"
	"errors"
	"io"
	"time"
)

// ErrNotFound is returned when an object does not exist.
var ErrNotFound = errors.New("object not found")

// Info describes a stored object.
type Info struct {
	Size    int64
	ModTime time.Time
}

// Object is an opened, seekable object body.
type Object interface {
	io.ReadSeeker
	io.Closer
}

// Store persists opaque objects under validated keys.
//
// Put must be atomic: readers either see the complete object or nothing, so a
// interrupted download can never be served as an APK.
type Store interface {
	Put(ctx context.Context, key string, body io.Reader) (Info, error)
	Open(ctx context.Context, key string) (Object, Info, error)
	Stat(ctx context.Context, key string) (Info, error)
	Delete(ctx context.Context, key string) error
	// Move publishes a staged object under its final key. The cache is
	// content-addressed, so the key is only known after the bytes have been read.
	Move(ctx context.Context, from, to string) (Info, error)
}
