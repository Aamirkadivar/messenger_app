package firebase

import (
	"context"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"log"
	"sync"

	firebase "firebase.google.com/go/v4"
	"firebase.google.com/go/v4/messaging"
	"google.golang.org/api/option"
)

// FCMService handles Firebase Cloud Messaging
type FCMService struct {
	client *messaging.Client
	ctx    context.Context
	mu     sync.Mutex
}

// Config holds Firebase configuration
type Config struct {
	ServiceAccountPath string `mapstructure:"FIREBASE_SERVICE_ACCOUNT_PATH"`
	CredentialsJSON    string `mapstructure:"FIREBASE_CREDENTIALS_JSON"`
}

var (
	instance *FCMService
	once       sync.Once
)

// NewFCMService creates a new FCM service instance
func NewFCMService(config Config) (*FCMService, error) {
	var firebaseApp *firebase.App
	var err error

	ctx := context.Background()

	if config.ServiceAccountPath != "" {
		opt := option.WithCredentialsFile(config.ServiceAccountPath)
		firebaseApp, err = firebase.NewApp(ctx, nil, opt)
		if err != nil {
			return nil, fmt.Errorf("error initializing firebase app: %v", err)
		}
	} else if config.CredentialsJSON != "" {
		// Try to parse as JSON string first, then as file path
		_, err2 := ioutil.ReadFile(config.CredentialsJSON)
		if err2 == nil {
			// It's a file path
			opt := option.WithCredentialsFile(config.CredentialsJSON)
			firebaseApp, err = firebase.NewApp(ctx, nil, opt)
			if err != nil {
				return nil, fmt.Errorf("error initializing firebase app: %v", err)
			}
		} else {
			// It's JSON content
			opt := option.WithCredentialsJSON([]byte(config.CredentialsJSON))
			firebaseApp, err = firebase.NewApp(ctx, nil, opt)
			if err != nil {
				return nil, fmt.Errorf("error initializing firebase app: %v", err)
			}
		}
	} else {
		log.Println("Firebase configuration not provided. Push notifications will be disabled.")
		return nil, nil
	}

	if firebaseApp == nil {
		return nil, nil
	}

	client, err := firebaseApp.Messaging(ctx)
	if err != nil {
		return nil, fmt.Errorf("error getting messaging client: %v", err)
	}

	return &FCMService{
		client: client,
		ctx:    ctx,
	}, nil
}

// GetInstance returns the singleton FCM service instance
func GetInstance() *FCMService {
	return instance
}

// InitFCM initializes the FCM service
func InitFCM(config Config) error {
	var err error
	once.Do(func() {
		instance, err = NewFCMService(config)
	})
	return err
}

// SendPushNotification sends a push notification to a specific device
func (f *FCMService) SendPushNotification(token string, title, body, senderName string, data map[string]string) error {
	if f == nil || f.client == nil {
		log.Println("FCM not configured, skipping push notification")
		return nil
	}

	// Prepare notification data
	notificationData := map[string]string{
		"title":       title,
		"body":        body,
		"sender_name": senderName,
		"type":        "message",
	}

	// Merge additional data
	for k, v := range data {
		notificationData[k] = v
	}

	// Create message
	message := &messaging.Message{
		Notification: &messaging.Notification{
			Title:    title,
			Body:      body,
		},
		Data: notificationData,
		Token: token,
	}

	// Send message
	response, err := f.client.Send(f.ctx, message)
	if err != nil {
		log.Printf("Failed to send push notification: %v", err)
		return err
	}

	log.Printf("Push notification sent successfully: %s", response)
	return nil
}

// SendBulkNotifications sends notifications to multiple devices
func (f *FCMService) SendBulkNotifications(tokens []string, title, body, senderName string, data map[string]string) (*messaging.BatchResponse, error) {
	if f == nil || f.client == nil {
		log.Println("FCM not configured, skipping bulk push notifications")
		return nil, nil
	}

	notificationData := map[string]string{
		"title":       title,
		"body":        body,
		"sender_name": senderName,
		"type":        "message",
	}
	for k, v := range data {
		notificationData[k] = v
	}

	message := &messaging.MulticastMessage{
		Tokens: tokens,
		Notification: &messaging.Notification{
			Title: title,
			Body:  body,
		},
		Data: notificationData,
	}

	response, err := f.client.SendMulticast(f.ctx, message)
	if err != nil {
		log.Printf("Failed to send bulk push notifications: %v", err)
		return nil, err
	}

	log.Printf("Bulk push notifications: %d sent, %d failed", response.SuccessCount, response.FailureCount)
	return response, nil
}

// SendGroupNotification sends a notification for a group message
func (f *FCMService) SendGroupNotification(groupID, senderID, senderName, messageContent, chatType string, memberTokens []string) error {
	if f == nil || f.client == nil {
		return nil
	}

	data := map[string]string{
		"chat_id":   groupID,
		"sender_id": senderID,
		"chat_type": chatType,
		"is_group":  "true",
	}

	_, err := f.SendBulkNotifications(memberTokens, senderName, messageContent, senderName, data)
	return err
}

// FormatMessageForJSON formats a message for JSON serialization in WebSocket
func FormatMessageForJSON(msg interface{}) ([]byte, error) {
	return json.Marshal(msg)
}

// ParseMessageFromJSON parses a WebSocket message from JSON
func ParseMessageFromJSON(data []byte, target interface{}) error {
	return json.Unmarshal(data, target)
}