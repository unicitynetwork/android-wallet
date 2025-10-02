#!/bin/bash

# Deploy Nostr relay with PostgreSQL persistent storage
# This enables multiple relay instances to share the same database

set -e

STACK_NAME="${1:-unicity-nostr-relay-20250927}"
REGION="${2:-me-central-1}"
DB_PASSWORD="${3:-$(openssl rand -base64 32 | tr -d '/@+=')}"

echo "🚀 Deploying Nostr relay with PostgreSQL..."
echo "   Stack: $STACK_NAME"
echo "   Region: $REGION"
echo ""

# Check if stack exists
if aws cloudformation describe-stacks --stack-name $STACK_NAME --region $REGION &>/dev/null; then
    echo "📦 Updating existing stack..."
    OPERATION="update-stack"
    WAIT_CONDITION="stack-update-complete"
else
    echo "📦 Creating new stack..."
    OPERATION="create-stack"
    WAIT_CONDITION="stack-create-complete"
fi

# Validate template
echo "✅ Validating CloudFormation template..."
aws cloudformation validate-template \
    --template-body file://nostr-relay-cloudformation.yaml \
    --region $REGION > /dev/null

# Deploy stack
aws cloudformation $OPERATION \
    --stack-name $STACK_NAME \
    --template-body file://nostr-relay-cloudformation.yaml \
    --parameters ParameterKey=DBPassword,ParameterValue="$DB_PASSWORD" \
    --capabilities CAPABILITY_IAM \
    --region $REGION

# Wait for completion
echo "⏳ Waiting for stack operation to complete (this may take 15-20 minutes)..."
aws cloudformation wait $WAIT_CONDITION \
    --stack-name $STACK_NAME \
    --region $REGION

# Get outputs
echo ""
echo "✅ Stack deployment complete!"
echo ""
echo "📊 Stack outputs:"
aws cloudformation describe-stacks \
    --stack-name $STACK_NAME \
    --region $REGION \
    --query 'Stacks[0].Outputs[?OutputKey==`WebSocketEndpoint`||OutputKey==`DatabaseEndpoint`||OutputKey==`DatabasePort`]' \
    --output table

# Get database endpoint
DB_ENDPOINT=$(aws cloudformation describe-stacks \
    --stack-name $STACK_NAME \
    --region $REGION \
    --query 'Stacks[0].Outputs[?OutputKey==`DatabaseEndpoint`].OutputValue' \
    --output text)

WEBSOCKET_URL=$(aws cloudformation describe-stacks \
    --stack-name $STACK_NAME \
    --region $REGION \
    --query 'Stacks[0].Outputs[?OutputKey==`WebSocketEndpoint`].OutputValue' \
    --output text)

echo ""
echo "🔗 Connection Details:"
echo "   WebSocket URL: $WEBSOCKET_URL"
echo "   Database: postgresql://nostrrelay:***@${DB_ENDPOINT}:5432/nostr"
echo ""
echo "⚠️  IMPORTANT: Save the database password securely!"
echo "   Password: $DB_PASSWORD"
echo ""
echo "🎯 What's new:"
echo "   ✓ PostgreSQL database for persistent storage"
echo "   ✓ All relay instances share the same database"
echo "   ✓ Events persist across container restarts"
echo "   ✓ Supports concurrent writes from multiple containers"
echo ""
echo "🧪 To test persistence:"
echo "   ./test-relay-persistence.sh $WEBSOCKET_URL"