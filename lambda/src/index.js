const crypto = require('crypto');

// DynamoDB client
const { DynamoDBClient } = require('@aws-sdk/client-dynamodb');
const { DynamoDBDocumentClient, PutCommand, GetCommand, UpdateCommand, ScanCommand } = require('@aws-sdk/lib-dynamodb');

const client = new DynamoDBClient({});
const ddb = DynamoDBDocumentClient.from(client);

const TABLE_NAME = process.env.TABLE_NAME || 'PaymentRequests';
const TTL_SECONDS = parseInt(process.env.TTL_SECONDS || '60');

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

    // 404 for unknown routes
    return corsResponse(404, { error: 'Not found' });

  } catch (error) {
    console.error('Error:', error);
    return corsResponse(500, { error: 'Internal server error' });
  }
};