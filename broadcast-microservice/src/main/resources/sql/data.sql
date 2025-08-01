-- Sample data for Broadcast Messaging System
-- This data is used for development and testing purposes

-- Insert sample admin users
INSERT INTO broadcast_messages (sender_id, sender_name, content, target_type, priority, category, status) VALUES
('admin-001', 'System Administrator', 'Welcome to the broadcast messaging system! This is a system-wide announcement.', 'ALL', 'HIGH', 'SYSTEM', 'ACTIVE'),
('admin-002', 'Security Team', 'Important security update: Please review the new security policies.', 'ALL', 'URGENT', 'SECURITY', 'ACTIVE'),
('admin-003', 'HR Department', 'Monthly newsletter: Company updates and announcements.', 'ALL', 'NORMAL', 'HR', 'ACTIVE'),
('admin-004', 'IT Support', 'Scheduled maintenance this weekend - systems will be unavailable.', 'ALL', 'HIGH', 'IT', 'ACTIVE'),
('admin-005', 'Management', 'Q4 results and company performance review.', 'SELECTED', 'NORMAL', 'BUSINESS', 'ACTIVE');
-- Insert sample user preferences
INSERT INTO user_preferences (user_id, notification_enabled, email_notifications, push_notifications, preferred_categories, timezone) VALUES
('user-001', true, true, true, '["SYSTEM", "SECURITY", "IT"]', 'UTC'),
('user-002', true, false, true, '["HR", "BUSINESS"]', 'America/New_York'),
('user-003', true, true, false, '["SYSTEM", "IT"]', 'Europe/London'),
('user-004', false, false, false, '[]', 'Asia/Tokyo'),
('user-005', true, true, true, '["ALL"]', 'UTC');
-- Insert sample user broadcast messages (for testing delivery tracking)
INSERT INTO user_broadcast_messages (broadcast_id, user_id, delivery_status, read_status) VALUES
(1, 'user-001', 'DELIVERED', 'READ'),
(1, 'user-002', 'DELIVERED', 'UNREAD'),
(1, 'user-003', 'DELIVERED', 'READ'),
(2, 'user-001', 'DELIVERED', 'READ'),
(2, 'user-002', 'PENDING', 'UNREAD'),
(3, 'user-003', 'DELIVERED', 'UNREAD'),
(4, 'user-001', 'PENDING', 'UNREAD'),
(5, 'user-002', 'DELIVERED', 'READ');
-- Insert sample user sessions
-- **FIX**: All sessions are now seeded as INACTIVE to prevent ghost connections.
-- A session will only become ACTIVE when a real connection is made from the UI.
INSERT INTO user_sessions (user_id, session_id, pod_id, connection_status, connected_at) VALUES
('user-001', 'session-001', 'pod-001', 'INACTIVE', CURRENT_TIMESTAMP),
('user-002', 'session-002', 'pod-001', 'INACTIVE', CURRENT_TIMESTAMP),
('user-003', 'session-003', 'pod-002', 'INACTIVE', CURRENT_TIMESTAMP),
('user-004', 'session-004', 'pod-002', 'INACTIVE', CURRENT_TIMESTAMP),
('user-005', 'session-005', 'pod-003', 'INACTIVE', CURRENT_TIMESTAMP);
-- Insert sample broadcast statistics
INSERT INTO broadcast_statistics (broadcast_id, total_targeted, total_delivered, total_read, total_failed, avg_delivery_time_ms) VALUES
(1, 5, 4, 2, 0, 150),
(2, 5, 3, 1, 0, 200),
(3, 5, 2, 0, 0, 180),
(4, 5, 1, 0, 0, 250),
(5, 2, 2, 1, 0, 120);