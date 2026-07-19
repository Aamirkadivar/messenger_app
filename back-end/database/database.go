package database

import (
	"log"
	"time"

	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
	"messenger-app/config"
)

var DB *gorm.DB

// InitDB initializes the database connection
func InitDB(cfg *config.Config) {
	dsn := cfg.DBUser + ":" + cfg.DBPassword + "@host=" + cfg.DBHost + " port=" + cfg.DBPort + " dbname=" + cfg.DBName + " " + "sslmode=" + cfg.DBSSLMode

	var gormLogger logger.Interface
	if cfg.Env == "development" {
		gormLogger = logger.Default
	} else {
		gormLogger = logger.Discard
	}

	var err error
	DB, err = gorm.Open(postgres.Open(dsn), &gorm.Config{
		Logger: gormLogger,
	})
	if err != nil {
		log.Fatalf("Failed to connect to database: %v", err)
	}

	sqlDB, err := DB.DB()
	if err != nil {
		log.Fatalf("Failed to get sql.DB: %v", err)
	}

	sqlDB.SetMaxIdleConns(cfg.DBMaxPoolSize)
	sqlDB.SetMaxOpenConns(cfg.DBMaxPoolSize)
	sqlDB.SetConnMaxLifetime(time.Hour)

	if err := sqlDB.Ping(); err != nil {
		log.Fatalf("Failed to ping database: %v", err)
	}

	log.Println("Connected to database successfully")
}

// CloseDB closes the database connection
func CloseDB() {
	if DB != nil {
		sqlDB, err := DB.DB()
		if err != nil {
			log.Printf("Failed to close database connection: %v", err)
			return
		}
		if err := sqlDB.Close(); err != nil {
			log.Printf("Failed to close database connection: %v", err)
		}
		log.Println("Database connection closed")
	}
}