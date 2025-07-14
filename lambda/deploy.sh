#!/bin/bash

# Simple deployment script for Lambda payment request API
set -e

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}🚀 Deploying NFC Wallet Payment Request API${NC}"

# Check if AWS CLI is installed
if ! command -v aws &> /dev/null; then
    echo -e "${RED}❌ AWS CLI is not installed. Please install it first.${NC}"
    echo "Run: brew install awscli"
    exit 1
fi

# Check if SAM CLI is installed
if ! command -v sam &> /dev/null; then
    echo -e "${RED}❌ SAM CLI is not installed. Please install it first.${NC}"
    echo "Run: brew tap aws/tap && brew install aws-sam-cli"
    exit 1
fi

# Check AWS credentials
if ! aws sts get-caller-identity &> /dev/null; then
    echo -e "${RED}❌ AWS credentials not configured. Please run 'aws configure' first.${NC}"
    exit 1
fi

# Get AWS account info
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
REGION=$(aws configure get region || echo "us-east-1")
STACK_NAME="nfc-wallet-payment-api"

echo -e "${YELLOW}📦 Installing dependencies...${NC}"
npm install --production

echo -e "${YELLOW}🔨 Building Lambda function...${NC}"
sam build

echo -e "${YELLOW}🚀 Deploying to AWS...${NC}"
echo "   Account: $ACCOUNT_ID"
echo "   Region: $REGION"
echo "   Stack: $STACK_NAME"

# Deploy with SAM
sam deploy \
    --stack-name $STACK_NAME \
    --capabilities CAPABILITY_IAM \
    --resolve-s3 \
    --no-confirm-changeset \
    --no-fail-on-empty-changeset

# Get the API URL
API_URL=$(aws cloudformation describe-stacks \
    --stack-name $STACK_NAME \
    --query 'Stacks[0].Outputs[?OutputKey==`ApiUrl`].OutputValue' \
    --output text)

if [ -n "$API_URL" ]; then
    echo -e "\n${GREEN}✅ Deployment successful!${NC}"
    echo -e "${GREEN}🌐 API URL: ${API_URL}${NC}"
    echo -e "\n${YELLOW}Update your Android app with this URL:${NC}"
    echo "   PaymentRequestApi.kt -> BASE_URL = \"$API_URL\""
else
    echo -e "${RED}❌ Failed to get API URL${NC}"
fi