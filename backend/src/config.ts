import dotenv from 'dotenv';

dotenv.config();

export const config = {
  port: parseInt(process.env.PORT || '3001', 10),
  requestExpirySeconds: parseInt(process.env.REQUEST_EXPIRY_SECONDS || '60', 10),
  cleanupIntervalSeconds: parseInt(process.env.CLEANUP_INTERVAL_SECONDS || '30', 10),
  corsOrigin: process.env.CORS_ORIGIN || '*',
};