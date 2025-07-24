const crypto = require('crypto');

// DynamoDB client
const { DynamoDBClient } = require('@aws-sdk/client-dynamodb');
const { DynamoDBDocumentClient, PutCommand, GetCommand, UpdateCommand, ScanCommand } = require('@aws-sdk/lib-dynamodb');

const client = new DynamoDBClient({});
const ddb = DynamoDBDocumentClient.from(client);

const TABLE_NAME = process.env.TABLE_NAME || 'PaymentRequests';
const TRANSFER_TABLE_NAME = process.env.TRANSFER_TABLE_NAME || 'TransferRequests';
const AGENTS_TABLE_NAME = process.env.AGENTS_TABLE_NAME || 'Agents';
const TTL_SECONDS = parseInt(process.env.TTL_SECONDS || '60');
const TRANSFER_TTL_SECONDS = parseInt(process.env.TRANSFER_TTL_SECONDS || '300'); // 5 minutes default
const AGENT_TTL_SECONDS = parseInt(process.env.AGENT_TTL_SECONDS || '600'); // 10 minutes default

// Helper to create CORS response
const corsResponse = (statusCode, body) => ({
  statusCode,
  headers: {
    'Content-Type': 'application/json',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, PUT, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type'
  },
  body: JSON.stringify(body)
});

// Helper to calculate distance between two GPS coordinates in km
const calculateDistance = (lat1, lon1, lat2, lon2) => {
  const R = 6371; // Radius of the Earth in km
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLon = (lon2 - lon1) * Math.PI / 180;
  const a = Math.sin(dLat/2) * Math.sin(dLat/2) +
    Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
    Math.sin(dLon/2) * Math.sin(dLon/2);
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
  return R * c;
};

// Main Lambda handler
exports.handler = async (event) => {
  // Handle OPTIONS for CORS
  if (event.httpMethod === 'OPTIONS') {
    return corsResponse(200, {});
  }

  const path = event.path;
  const method = event.httpMethod;

  try {
    // POST /payment-requests
    if (method === 'POST' && path === '/payment-requests') {
      const body = JSON.parse(event.body);
      const requestId = crypto.randomBytes(16).toString('hex');
      const timestamp = Date.now();
      const ttl = Math.floor(timestamp / 1000) + TTL_SECONDS;

      const item = {
        requestId,
        recipientAddress: body.recipientAddress,
        currencySymbol: body.currencySymbol || null,
        amount: body.amount || null,
        createdAt: new Date(timestamp).toISOString(),
        expiresAt: new Date(timestamp + TTL_SECONDS * 1000).toISOString(),
        status: 'pending',
        ttl // DynamoDB TTL attribute
      };

      await ddb.send(new PutCommand({
        TableName: TABLE_NAME,
        Item: item
      }));

      let qrData = `nfcwallet://payment-request?id=${requestId}&recipient=${encodeURIComponent(body.recipientAddress)}`;
      if (body.currencySymbol) {
        qrData += `&currency=${encodeURIComponent(body.currencySymbol)}`;
      }
      if (body.amount) {
        qrData += `&amount=${encodeURIComponent(body.amount)}`;
      }
      
      return corsResponse(201, {
        ...item,
        qrData
      });
    }

    // GET /payment-requests/:id
    if (method === 'GET' && path.startsWith('/payment-requests/')) {
      const requestId = path.split('/')[2];
      
      const result = await ddb.send(new GetCommand({
        TableName: TABLE_NAME,
        Key: { requestId }
      }));

      if (!result.Item) {
        return corsResponse(404, { error: 'Payment request not found' });
      }

      // Check if expired
      const expiresAt = new Date(result.Item.expiresAt);
      if (expiresAt < new Date()) {
        return corsResponse(404, { error: 'Payment request expired' });
      }

      return corsResponse(200, result.Item);
    }

    // PUT /payment-requests/:id/complete
    if (method === 'PUT' && path.endsWith('/complete')) {
      const requestId = path.split('/')[2];
      const body = JSON.parse(event.body);

      const result = await ddb.send(new UpdateCommand({
        TableName: TABLE_NAME,
        Key: { requestId },
        UpdateExpression: 'SET #status = :status, paymentDetails = :details, completedAt = :completedAt',
        ExpressionAttributeNames: {
          '#status': 'status'
        },
        ExpressionAttributeValues: {
          ':status': 'completed',
          ':details': body,
          ':completedAt': new Date().toISOString()
        },
        ReturnValues: 'ALL_NEW'
      }));

      return corsResponse(200, result.Attributes);
    }

    // GET /payment-requests (list all - for testing)
    if (method === 'GET' && path === '/payment-requests') {
      const result = await ddb.send(new ScanCommand({
        TableName: TABLE_NAME,
        Limit: 100
      }));

      return corsResponse(200, result.Items || []);
    }

    // POST /transfer-requests
    if (method === 'POST' && path === '/transfer-requests') {
      const body = JSON.parse(event.body);
      const requestId = crypto.randomBytes(16).toString('hex');
      const timestamp = Date.now();
      const ttl = Math.floor(timestamp / 1000) + TRANSFER_TTL_SECONDS;

      const item = {
        requestId,
        senderTag: body.senderTag || 'anonymous',
        recipientTag: body.recipientTag, // Required
        assetType: body.assetType, // 'crypto' or 'token'
        assetName: body.assetName, // e.g., 'BTC', 'ETH', token name
        amount: body.amount,
        message: body.message || null,
        createdAt: new Date(timestamp).toISOString(),
        expiresAt: new Date(timestamp + TRANSFER_TTL_SECONDS * 1000).toISOString(),
        status: 'pending',
        ttl // DynamoDB TTL attribute
      };

      await ddb.send(new PutCommand({
        TableName: TRANSFER_TABLE_NAME,
        Item: item
      }));

      return corsResponse(201, item);
    }

    // GET /transfer-requests/pending/:recipientTag
    if (method === 'GET' && path.startsWith('/transfer-requests/pending/')) {
      const recipientTag = decodeURIComponent(path.split('/')[3]);
      
      // Scan for pending transfers for this recipient
      const result = await ddb.send(new ScanCommand({
        TableName: TRANSFER_TABLE_NAME,
        FilterExpression: 'recipientTag = :tag AND #status = :status',
        ExpressionAttributeNames: {
          '#status': 'status'
        },
        ExpressionAttributeValues: {
          ':tag': recipientTag,
          ':status': 'pending'
        },
        Limit: 50
      }));

      // Filter out expired items
      const now = new Date();
      const validItems = (result.Items || []).filter(item => {
        const expiresAt = new Date(item.expiresAt);
        return expiresAt > now;
      });

      return corsResponse(200, validItems);
    }

    // PUT /transfer-requests/:id/accept
    if (method === 'PUT' && path.endsWith('/accept')) {
      const requestId = path.split('/')[2];

      const result = await ddb.send(new UpdateCommand({
        TableName: TRANSFER_TABLE_NAME,
        Key: { requestId },
        UpdateExpression: 'SET #status = :status, acceptedAt = :acceptedAt',
        ExpressionAttributeNames: {
          '#status': 'status'
        },
        ExpressionAttributeValues: {
          ':status': 'accepted',
          ':acceptedAt': new Date().toISOString()
        },
        ReturnValues: 'ALL_NEW'
      }));

      return corsResponse(200, result.Attributes);
    }

    // PUT /transfer-requests/:id/reject
    if (method === 'PUT' && path.endsWith('/reject')) {
      const requestId = path.split('/')[2];

      const result = await ddb.send(new UpdateCommand({
        TableName: TRANSFER_TABLE_NAME,
        Key: { requestId },
        UpdateExpression: 'SET #status = :status, rejectedAt = :rejectedAt',
        ExpressionAttributeNames: {
          '#status': 'status'
        },
        ExpressionAttributeValues: {
          ':status': 'rejected',
          ':rejectedAt': new Date().toISOString()
        },
        ReturnValues: 'ALL_NEW'
      }));

      return corsResponse(200, result.Attributes);
    }

    // POST /agents/update-location
    if (method === 'POST' && path === '/agents/update-location') {
      const body = JSON.parse(event.body);
      const timestamp = Date.now();
      const ttl = Math.floor(timestamp / 1000) + AGENT_TTL_SECONDS;

      if (!body.unicityTag || !body.latitude || !body.longitude) {
        return corsResponse(400, { error: 'Missing required fields: unicityTag, latitude, longitude' });
      }

      const item = {
        unicityTag: body.unicityTag,
        latitude: body.latitude,
        longitude: body.longitude,
        isActive: body.isActive !== false, // Default to true
        lastUpdateAt: new Date(timestamp).toISOString(),
        ttl // DynamoDB TTL attribute
      };

      await ddb.send(new PutCommand({
        TableName: AGENTS_TABLE_NAME,
        Item: item
      }));

      return corsResponse(200, { message: 'Location updated successfully' });
    }

    // GET /agents/nearby?lat=X&lon=Y&maxDistance=Z
    if (method === 'GET' && path === '/agents/nearby') {
      const params = event.queryStringParameters || {};
      const userLat = parseFloat(params.lat);
      const userLon = parseFloat(params.lon);
      const maxDistance = parseFloat(params.maxDistance || '100'); // Default 100km

      if (isNaN(userLat) || isNaN(userLon)) {
        return corsResponse(400, { error: 'Invalid latitude or longitude' });
      }

      // Scan all agents (in production, use geospatial indexing)
      const result = await ddb.send(new ScanCommand({
        TableName: AGENTS_TABLE_NAME,
        FilterExpression: 'isActive = :active',
        ExpressionAttributeValues: {
          ':active': true
        }
      }));

      // Calculate distances and filter
      const now = new Date();
      const nearbyAgents = (result.Items || [])
        .map(agent => {
          const distance = calculateDistance(userLat, userLon, agent.latitude, agent.longitude);
          return { ...agent, distance };
        })
        .filter(agent => agent.distance <= maxDistance)
        .sort((a, b) => a.distance - b.distance)
        .slice(0, 20); // Max 20 agents

      return corsResponse(200, nearbyAgents);
    }

    // DELETE /agents/:unicityTag
    if (method === 'DELETE' && path.startsWith('/agents/')) {
      const unicityTag = decodeURIComponent(path.split('/')[2]);

      await ddb.send(new UpdateCommand({
        TableName: AGENTS_TABLE_NAME,
        Key: { unicityTag },
        UpdateExpression: 'SET isActive = :inactive',
        ExpressionAttributeValues: {
          ':inactive': false
        }
      }));

      return corsResponse(200, { message: 'Agent deactivated successfully' });
    }

    // 404 for unknown routes
    return corsResponse(404, { error: 'Not found' });

  } catch (error) {
    console.error('Error:', error);
    return corsResponse(500, { error: 'Internal server error' });
  }
};