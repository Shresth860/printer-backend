UPDATE printer SET last_status = 'ONLINE' WHERE last_status = 'CONNECTED';
UPDATE printer SET last_status = 'OFFLINE' WHERE last_status = 'DISCONNECTED';
