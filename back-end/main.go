package main

import (
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"messenger-app/config"
	"messenger-app/database"
	"messenger-app/firebase"
	"messenger-app/handlers"
	"messenger-app/middleware"
	"messenger-app/models"
	"messenger-app/websocket"

	"github.com/gofiber/fiber/v2"
	fiberlog "github.com/gofiber/fiber/v2/log"
	"github.com/joho/godotenv"
)

func main() {
	// Load .env file if present (ignored if missing; real env vars still take precedence)
	if err := godotenv.Load(); err != nil {
		log.Println("No .env file found, using existing environment variables")
	}

	// Load configuration
	cfg := config.LoadConfig()

	// Initialize database
	database.InitDB(cfg)
	defer database.CloseDB()

	// Run migrations
	if err := models.MigrateDB(database.DB); err != nil {
		log.Fatalf("Failed to run migrations: %v", err)
	}

	// Initialize WebSocket Hub
	hub := websocket.NewHub()
	go hub.Run()

	// Initialize Firebase (optional)
	if err := firebase.InitFCM(firebase.Config{
		ServiceAccountPath: cfg.FirebaseServiceAccountPath,
	}); err != nil {
		fiberlog.Warnf("Failed to initialize Firebase: %v", err)
	}

	// Initialize services
	authService := handlers.NewAuthService()
	messageService := handlers.NewMessageService(hub)
	groupService := handlers.NewGroupService(hub)
	userHandler := handlers.NewUserHandler()
	cryptoHandler := handlers.NewCryptoHandler()
	uploadHandler := handlers.NewUploadHandler()
	callService := handlers.NewCallService(cfg)

	if err := handlers.EnsureUploadDirs(); err != nil {
		log.Fatalf("Failed to create upload directories: %v", err)
	}
	_ = middleware.AuthMiddleware // referenced for documentation

	// Create Fiber app
	app := fiber.New(fiber.Config{
		Prefork:               false,
		DisableStartupMessage: true,
		ErrorHandler:          middleware.ErrorHandler,
		BodyLimit:             25 * 1024 * 1024, // 25MB - raised from 10MB for file/image attachments
		ReadTimeout:           30 * time.Second,
		WriteTimeout:          30 * time.Second,
		IdleTimeout:           120 * time.Second,
	})

	// Middleware
	app.Use(middleware.CORS())

	// Uploaded avatars. Served unauthenticated: filenames are random UUIDs, so
	// they are unguessable, and this avoids every avatar request needing a
	// token (image loaders don't carry one). Nothing sensitive is stored here.
	app.Static("/"+handlers.UploadRoot, "./"+handlers.UploadRoot, fiber.Static{
		Browse:    false,
		ByteRange: true,
		MaxAge:    86400,
	})

	// Health check
	app.Get("/health", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{
			"status":    "healthy",
			"timestamp": time.Now().Unix(),
		})
	})

	// API v1 routes
	api := app.Group("/api/v1")

	// Public routes
	auth := api.Group("/auth")
	auth.Post("/register", authService.Register)
	auth.Post("/login", authService.Login)
	auth.Post("/refresh", authService.RefreshToken)

	// Protected routes
	protected := api.Group("")
	protected.Use(middleware.AuthMiddleware(cfg))

	// User routes
	userRoutes := protected.Group("/users")
	userRoutes.Get("/me", userHandler.GetMe)
	userRoutes.Put("/me", userHandler.UpdateMe)
	userRoutes.Get("/search", userHandler.SearchUsers)
	userRoutes.Get("/:user_id/presence", userHandler.GetUserPresence)
	userRoutes.Post("/me/avatar", uploadHandler.UploadMyAvatar)

	// Chat routes
	chatRoutes := protected.Group("/chats")
	chatRoutes.Get("/", messageService.GetChatsByUserID)
	chatRoutes.Post("/direct/:contact_id", messageService.GetDirectChat)
	chatRoutes.Get("/:chat_id", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{"error": "Not implemented yet"})
	})
	chatRoutes.Get("/:chat_id/participants", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{"error": "Not implemented yet"})
	})
	chatRoutes.Post("/:chat_id/participants", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{"error": "Not implemented yet"})
	})
	chatRoutes.Delete("/:chat_id/participants/:user_id", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{"error": "Not implemented yet"})
	})
	chatRoutes.Post("/:chat_id/read", messageService.MarkAsRead)
	// Removes the chat from the caller's list only - see DeleteChat.
	chatRoutes.Delete("/:chat_id", messageService.DeleteChat)

	// Group routes
	groupRoutes := protected.Group("/groups")
	groupRoutes.Post("/", groupService.CreateGroup)
	groupRoutes.Get("/", groupService.GetGroups)
	groupRoutes.Get("/:chat_id", groupService.GetGroupInfo)
	groupRoutes.Put("/:chat_id", groupService.UpdateGroup)
	groupRoutes.Post("/:chat_id/members", groupService.AddMembers)
	groupRoutes.Delete("/:chat_id/members/:member_id", groupService.RemoveMember)
	groupRoutes.Put("/:chat_id/members/:member_id/role", groupService.UpdateMemberRole)
	groupRoutes.Delete("/:chat_id", groupService.DeleteGroup)
	groupRoutes.Post("/:chat_id/avatar", uploadHandler.UploadGroupAvatar)
	groupRoutes.Post("/:chat_id/leave", groupService.LeaveGroup)
	groupRoutes.Get("/:chat_id/search-users", groupService.SearchUsers)
	groupRoutes.Post("/:chat_id/sender-key", groupService.PublishSenderKey)
	groupRoutes.Get("/:chat_id/sender-keys", groupService.GetSenderKeys)

	// Call routes - signaling itself is WS-only (see websocket/calls.go);
	// these are just history + the ICE server list needed to start one.
	callRoutes := protected.Group("/calls")
	callRoutes.Get("/", callService.GetCallHistory)
	callRoutes.Get("/ice-servers", callService.GetIceServers)

	// Message routes
	messageRoutes := protected.Group("/messages")
	messageRoutes.Post("/", messageService.SendMessage)
	messageRoutes.Post("/voice", uploadHandler.UploadVoice)
	messageRoutes.Post("/attachment", uploadHandler.UploadAttachment)
	messageRoutes.Post("/video-note", uploadHandler.UploadVideoNote)
	messageRoutes.Post("/video-thumb", uploadHandler.UploadVideoThumb)
	messageRoutes.Get("/:chat_id", messageService.GetMessages)
	messageRoutes.Delete("/:chat_id/:message_id", messageService.DeleteMessage)
	messageRoutes.Post("/:chat_id/unread", messageService.GetUnreadCount)

	// Crypto/E2EE routes
	// E2EE: the server only ever stores/serves *public* keys. Keypair
	// generation happens on the client and private keys never leave the
	// device, so the old server-side /generate-keypair route (which stored
	// private keys in the DB) is intentionally gone.
	cryptoRoutes := protected.Group("/crypto")
	cryptoRoutes.Post("/public-key", cryptoHandler.SavePublicKey)
	cryptoRoutes.Get("/public-key", cryptoHandler.GetPublicKey)
	cryptoRoutes.Get("/public-key/:user_id", cryptoHandler.GetPublicKeyByUserId)

	// WebSocket route
	app.Get("/ws", middleware.WebSocketAuth(cfg), websocket.WSHandler(hub))

	// Start server
	addr := ":" + cfg.AppPort
	log.Printf("Starting messenger server on %s", addr)
	log.Printf("API available at http://localhost%s/api/v1", addr)
	log.Printf("WebSocket available at ws://localhost%s/ws", addr)

	go func() {
		if err := app.Listen(addr); err != nil {
			fiberlog.Fatalf("Failed to start server: %v", err)
		}
	}()

	// Graceful shutdown
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	fiberlog.Info("Shutting down server...")
	if err := app.Shutdown(); err != nil {
		fiberlog.Fatalf("Server forced to shutdown: %v", err)
	}

	fiberlog.Info("Server exited properly")
}
