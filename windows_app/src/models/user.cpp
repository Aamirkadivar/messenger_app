#include "user.h"

// User implementation - copy constructor and assignment operator
User::User(const User& other) {
    m_id = other.m_id;
    m_username = other.m_username;
    m_displayName = other.m_displayName;
    m_avatar = other.m_avatar;
    m_online = other.m_online;
    m_lastSeen = other.m_lastSeen;
    m_publicKey = other.m_publicKey;
}

User& User::operator=(const User& other) {
    if (this != &other) {
        m_id = other.m_id;
        m_username = other.m_username;
        m_displayName = other.m_displayName;
        m_avatar = other.m_avatar;
        m_online = other.m_online;
        m_lastSeen = other.m_lastSeen;
        m_publicKey = other.m_publicKey;
    }
    return *this;
}