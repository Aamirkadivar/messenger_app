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
)

func main() {
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
	_ = middleware.AuthMiddleware // referenced for documentation

	// Create Fiber app
	app := fiber.New(fiber.Config{
		Prefork:               false,
		DisableStartupMessage: true,
		ErrorHandler:          middleware.ErrorHandler,
		BodyLimit:             10 * 1024 * 1024, // 10MB
		ReadTimeout:           30 * time.Second,
		WriteTimeout:          30 * time.Second,
		IdleTimeout:           120 * time.Second,
	})

	// Middleware
	app.Use(middleware.CORS())

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

	// Chat routes (placeholder - implement ChatHandler or use messageService)
	chatRoutes := protected.Group("/chats")
	chatRoutes.Get("/", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{"error": "Not implemented yet"})
	})
	chatRoutes.Post("/direct", messageService.GetDirectChat)
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

	// Group routes
	groupRoutes := protected.Group("/groups")
	groupRoutes.Post("/", groupService.CreateGroup)
	groupRoutes.Get("/", groupService.GetGroups)
	groupRoutes.Get("/:chat_id", groupService.GetGroupInfo)
	groupRoutes.Put("/:chat_id", groupService.UpdateGroup)
	groupRoutes.Post("/:chat_id/members", groupService.AddMembers)
	groupRoutes.Delete("/:chat_id/members/:member_id", groupService.RemoveMember)
	groupRoutes.Post("/:chat_id/leave", groupService.LeaveGroup)
	groupRoutes.Get("/:chat_id/search-users", groupService.SearchUsers)

	// Message routes
	messageRoutes := protected.Group("/messages")
	messageRoutes.Post("/", messageService.SendMessage)
	messageRoutes.Get("/:chat_id", messageService.GetMessages)
	messageRoutes.Delete("/:chat_id/:message_id", messageService.DeleteMessage)
	messageRoutes.Post("/:chat_id/unread", messageService.GetUnreadCount)

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