# Lambda Payment Request API

Serverless backend for NFC Wallet payment requests using AWS Lambda, API Gateway, and DynamoDB.

## Prerequisites

1. **AWS Account** with credentials configured
2. **AWS CLI** installed: `brew install awscli`
3. **SAM CLI** installed: `brew tap aws/tap && brew install aws-sam-cli`
4. Configure AWS credentials: `aws configure`

## Deploy with One Command

```bash
cd lambda
./deploy.sh
```

That's it! The script will:
- Install dependencies
- Build the Lambda function
- Deploy to AWS
- Output your API URL

## Update Android App

After deployment, update the API URL in your Android app:

```kotlin
// app/src/main/java/com/unicity/nfcwalletdemo/network/PaymentRequestApi.kt
private const val BASE_URL = "YOUR_API_URL_HERE"
```

## Architecture

- **Lambda Function**: Handles all API requests
- **API Gateway**: HTTPS endpoint with CORS enabled
- **DynamoDB**: Stores payment requests with automatic TTL deletion
- **CloudFormation**: Infrastructure as code via SAM

## API Endpoints

- `POST /payment-requests` - Create new payment request
- `GET /payment-requests/{id}` - Get payment request details
- `PUT /payment-requests/{id}/complete` - Mark payment as completed
- `GET /payment-requests` - List all requests (testing)

## Costs

For typical demo usage:
- **Lambda**: Free tier (1M requests/month)
- **DynamoDB**: Free tier (25GB storage)
- **API Gateway**: ~$3.50 per million requests

## Customization

- Change TTL: Edit `TTL_SECONDS` in `template.yaml`
- Modify endpoints: Edit `src/index.js`
- Change stack name: Edit `STACK_NAME` in `deploy.sh`

## Teardown / Cleanup

To remove all AWS resources:

```bash
cd lambda
./teardown.sh
```

This will:
- Ask for confirmation before deleting
- Delete the CloudFormation stack
- Clean up S3 deployment buckets
- Remove all Lambda, API Gateway, and DynamoDB resources