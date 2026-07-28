package handlers

import (
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"

	"messenger-app/database"
	"messenger-app/middleware"
	"messenger-app/models"

	"github.com/gofiber/fiber/v2"
	"github.com/google/uuid"
)

// UploadHandler serves avatar uploads for users and groups.
type UploadHandler struct{}

func NewUploadHandler() *UploadHandler { return &UploadHandler{} }

const (
	// UploadRoot is the on-disk directory backing the /uploads route.
	UploadRoot = "uploads"
	// AvatarDir is relative to UploadRoot.
	AvatarDir = "avatars"
	// maxAvatarBytes caps a single avatar. Fiber's global BodyLimit is 10MB;
	// avatars have no reason to approach that.
	maxAvatarBytes = 5 * 1024 * 1024
	// sniffLen is what http.DetectContentType reads.
	sniffLen = 512
)

// allowedImageTypes maps a sniffed MIME type to the extension we store it as.
// The client's filename and its extension are never trusted - the type is
// determined from the file's own magic bytes and the name is regenerated.
var allowedImageTypes = map[string]string{
	"image/jpeg": ".jpg",
	"image/png":  ".png",
	"image/gif":  ".gif",
	"image/webp": ".webp",
}

// EnsureUploadDirs creates the upload tree at startup.
func EnsureUploadDirs() error {
	if err := os.MkdirAll(filepath.Join(UploadRoot, AvatarDir), 0o755); err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Join(UploadRoot, VoiceDir), 0o755); err != nil {
		return err
	}
	return os.MkdirAll(filepath.Join(UploadRoot, AttachmentDir), 0o755)
}

// saveAvatar validates and writes an uploaded image, returning the public path.
//
// Security notes, in order of importance:
//   - The stored filename is a fresh UUID, so a malicious client filename
//     ("../../main.go", "x.php") can never influence the write path.
//   - The type comes from sniffing the content, not the extension, so renaming
//     a script to .png doesn't get it stored as an image.
//   - Size is capped before the file is copied, not after.
func saveAvatar(fileHeader *multipart.FileHeader) (string, error) {
	if fileHeader.Size > maxAvatarBytes {
		return "", fmt.Errorf("image must be %d MB or smaller", maxAvatarBytes/(1024*1024))
	}

	src, err := fileHeader.Open()
	if err != nil {
		return "", fmt.Errorf("could not read upload")
	}
	defer src.Close()

	head := make([]byte, sniffLen)
	n, err := io.ReadFull(src, head)
	if err != nil && err != io.ErrUnexpectedEOF && err != io.EOF {
		return "", fmt.Errorf("could not read upload")
	}
	head = head[:n]

	ext, ok := allowedImageTypes[http.DetectContentType(head)]
	if !ok {
		return "", fmt.Errorf("unsupported image type - use JPEG, PNG, GIF or WebP")
	}

	if _, err := src.Seek(0, io.SeekStart); err != nil {
		return "", fmt.Errorf("could not read upload")
	}

	name := uuid.New().String() + ext
	diskPath := filepath.Join(UploadRoot, AvatarDir, name)

	dst, err := os.Create(diskPath)
	if err != nil {
		return "", fmt.Errorf("could not store image")
	}
	defer dst.Close()

	// LimitReader rather than trusting the declared size, so a lying
	// Content-Length can't be used to write an oversized file.
	if _, err := io.Copy(dst, io.LimitReader(src, maxAvatarBytes)); err != nil {
		os.Remove(diskPath)
		return "", fmt.Errorf("could not store image")
	}

	// A root-relative path, not an absolute URL: the server's host/IP changes
	// between networks, and clients cache these values. Clients resolve it
	// against whatever base URL they are already using.
	return "/" + UploadRoot + "/" + AvatarDir + "/" + name, nil
}

// VoiceDir is relative to UploadRoot.
const VoiceDir = "voice"

// maxVoiceBytes caps a single voice note.
const maxVoiceBytes = 10 * 1024 * 1024

// UploadVoice stores a voice note and returns its path.
//
// Unlike avatars, the payload is NOT content-sniffed. For direct chats the
// client encrypts the audio before upload, so what arrives is an opaque
// ciphertext blob - indistinguishable from random bytes, and deliberately so.
// Validating it as audio/* would break exactly the case we most want to
// support. The protections that remain are a size cap and a server-generated
// filename, and the blob is only ever served back verbatim, never executed or
// interpreted.
func (h *UploadHandler) UploadVoice(c *fiber.Ctx) error {
	if middleware.GetCurrentUserID(c) == uuid.Nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
			"error": "unauthorized",
		})
	}

	fileHeader, err := c.FormFile("file")
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "invalid request",
			"message": "Expected a file in the 'file' field",
		})
	}
	if fileHeader.Size > maxVoiceBytes {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "too large",
			"message": fmt.Sprintf("Voice note must be %d MB or smaller", maxVoiceBytes/(1024*1024)),
		})
	}

	src, err := fileHeader.Open()
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "invalid request",
			"message": "Could not read upload",
		})
	}
	defer src.Close()

	// .bin, not .m4a: the stored bytes are ciphertext, and naming them after a
	// media type they can't be decoded as would be misleading.
	name := uuid.New().String() + ".bin"
	diskPath := filepath.Join(UploadRoot, VoiceDir, name)

	dst, err := os.Create(diskPath)
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Could not store voice note",
		})
	}
	defer dst.Close()

	written, err := io.Copy(dst, io.LimitReader(src, maxVoiceBytes))
	if err != nil {
		os.Remove(diskPath)
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Could not store voice note",
		})
	}

	return c.JSON(fiber.Map{
		"message":   "Voice note stored",
		"file_url":  "/" + UploadRoot + "/" + VoiceDir + "/" + name,
		"file_size": written,
	})
}

// AttachmentDir is relative to UploadRoot.
const AttachmentDir = "attachments"

// maxAttachmentBytes caps a single file/image attachment. Matches the
// server's global BodyLimit (main.go) - no point allowing a per-handler cap
// higher than what Fiber will already reject the request body for.
const maxAttachmentBytes = 25 * 1024 * 1024

// UploadAttachment stores a generic file or image message attachment and
// returns its path.
//
// Not content-sniffed, deliberately: like voice notes, an attachment sent in
// an E2EE direct chat arrives as opaque ciphertext (the client encrypts the
// bytes before upload), indistinguishable from random data - sniffing it as
// an image/document would reject exactly the case this exists to support.
// The client supplies the original filename and declared content type as
// plain-text metadata (not hidden by encryption, same as a voice note's
// duration) purely for display before the bytes are fetched and decrypted.
func (h *UploadHandler) UploadAttachment(c *fiber.Ctx) error {
	if middleware.GetCurrentUserID(c) == uuid.Nil {
		return c.Status(http.StatusUnauthorized).JSON(fiber.Map{
			"error": "unauthorized",
		})
	}

	fileHeader, err := c.FormFile("file")
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "invalid request",
			"message": "Expected a file in the 'file' field",
		})
	}
	if fileHeader.Size > maxAttachmentBytes {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "too large",
			"message": fmt.Sprintf("Attachment must be %d MB or smaller", maxAttachmentBytes/(1024*1024)),
		})
	}

	src, err := fileHeader.Open()
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "invalid request",
			"message": "Could not read upload",
		})
	}
	defer src.Close()

	// .bin, same reasoning as voice notes: the stored bytes may well be
	// ciphertext, so naming them after a type they can't necessarily be
	// decoded as would be misleading. The client's own file_name metadata is
	// what drives the filename shown in the UI.
	name := uuid.New().String() + ".bin"
	diskPath := filepath.Join(UploadRoot, AttachmentDir, name)

	dst, err := os.Create(diskPath)
	if err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Could not store attachment",
		})
	}
	defer dst.Close()

	written, err := io.Copy(dst, io.LimitReader(src, maxAttachmentBytes))
	if err != nil {
		os.Remove(diskPath)
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Could not store attachment",
		})
	}

	return c.JSON(fiber.Map{
		"message":   "Attachment stored",
		"file_url":  "/" + UploadRoot + "/" + AttachmentDir + "/" + name,
		"file_size": written,
	})
}

// UploadMyAvatar sets the current user's profile picture.
func (h *UploadHandler) UploadMyAvatar(c *fiber.Ctx) error {
	userID := middleware.GetCurrentUserID(c)

	fileHeader, err := c.FormFile("file")
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "invalid request",
			"message": "Expected a file in the 'file' field",
		})
	}

	path, err := saveAvatar(fileHeader)
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "invalid image",
			"message": err.Error(),
		})
	}

	if err := database.DB.Model(&models.User{}).
		Where("id = ?", userID).
		Update("avatar_url", path).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Failed to save avatar",
		})
	}

	return c.JSON(fiber.Map{
		"message":    "Avatar updated",
		"avatar_url": path,
	})
}

// UploadGroupAvatar sets a group's picture. Admins only.
func (h *UploadHandler) UploadGroupAvatar(c *fiber.Ctx) error {
	chatID := c.Params("chat_id")
	userID := middleware.GetCurrentUserID(c)

	var participant models.ChatParticipant
	if err := database.DB.
		Where("chat_id = ? AND user_id = ? AND role = ? AND left_at IS NULL", chatID, userID, "admin").
		First(&participant).Error; err != nil {
		return c.Status(http.StatusForbidden).JSON(fiber.Map{
			"error":   "forbidden",
			"message": "Only group admins can change the group picture",
		})
	}

	fileHeader, err := c.FormFile("file")
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "invalid request",
			"message": "Expected a file in the 'file' field",
		})
	}

	path, err := saveAvatar(fileHeader)
	if err != nil {
		return c.Status(http.StatusBadRequest).JSON(fiber.Map{
			"error":   "invalid image",
			"message": err.Error(),
		})
	}

	if err := database.DB.Model(&models.Chat{}).
		Where("id = ?", chatID).
		Update("avatar_url", path).Error; err != nil {
		return c.Status(http.StatusInternalServerError).JSON(fiber.Map{
			"error":   "internal error",
			"message": "Failed to save group picture",
		})
	}

	return c.JSON(fiber.Map{
		"message":    "Group picture updated",
		"avatar_url": path,
	})
}
