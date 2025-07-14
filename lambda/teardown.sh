#!/bin/bash

# Teardown script for Lambda payment request API
set -e

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

STACK_NAME="nfc-wallet-payment-api"

echo -e "${YELLOW}⚠️  WARNING: This will delete all AWS resources for the payment API${NC}"
echo -e "${YELLOW}Stack to be deleted: ${STACK_NAME}${NC}"
echo ""
read -p "Are you sure you want to continue? (yes/no): " confirmation

if [ "$confirmation" != "yes" ]; then
    echo -e "${GREEN}✅ Teardown cancelled${NC}"
    exit 0
fi

# Check if AWS CLI is installed
if ! command -v aws &> /dev/null; then
    echo -e "${RED}❌ AWS CLI is not installed. Please install it first.${NC}"
    echo "Run: brew install awscli"
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

echo -e "${YELLOW}🗑️  Deleting CloudFormation stack...${NC}"
echo "   Account: $ACCOUNT_ID"
echo "   Region: $REGION"
echo "   Stack: $STACK_NAME"

# Check if stack exists
if ! aws cloudformation describe-stacks --stack-name $STACK_NAME &> /dev/null; then
    echo -e "${YELLOW}Stack '$STACK_NAME' not found. Nothing to delete.${NC}"
    exit 0
fi

# Get S3 bucket name (SAM creates deployment bucket)
BUCKET_NAME=$(aws cloudformation describe-stack-resource \
    --stack-name $STACK_NAME \
    --logical-resource-id ServerlessDeploymentBucket \
    --query 'StackResourceDetail.PhysicalResourceId' \
    --output text 2>/dev/null || echo "")

# Delete the stack
aws cloudformation delete-stack --stack-name $STACK_NAME

echo -e "${YELLOW}⏳ Waiting for stack deletion to complete...${NC}"

# Wait for deletion to complete
aws cloudformation wait stack-delete-complete --stack-name $STACK_NAME

echo -e "${GREEN}✅ Stack deleted successfully${NC}"

# Clean up SAM deployment bucket if it exists
if [ -n "$BUCKET_NAME" ] && [ "$BUCKET_NAME" != "None" ]; then
    echo -e "${YELLOW}🗑️  Cleaning up deployment bucket: $BUCKET_NAME${NC}"
    
    # Check if bucket exists
    if aws s3 ls "s3://$BUCKET_NAME" &> /dev/null; then
        # Empty the bucket first
        aws s3 rm "s3://$BUCKET_NAME" --recursive
        # Delete the bucket
        aws s3 rb "s3://$BUCKET_NAME"
        echo -e "${GREEN}✅ Deployment bucket deleted${NC}"
    fi
fi

# Clean up any SAM-managed buckets
echo -e "${YELLOW}🔍 Looking for SAM-managed buckets...${NC}"
SAM_BUCKETS=$(aws s3 ls | grep -E "aws-sam-cli-managed-default-samclisourcebucket" | awk '{print $3}' || echo "")

if [ -n "$SAM_BUCKETS" ]; then
    echo -e "${YELLOW}Found SAM-managed buckets. Delete them? (yes/no):${NC}"
    read -p "" delete_sam_buckets
    
    if [ "$delete_sam_buckets" == "yes" ]; then
        for bucket in $SAM_BUCKETS; do
            echo -e "${YELLOW}🗑️  Deleting bucket: $bucket${NC}"
            aws s3 rm "s3://$bucket" --recursive
            aws s3 rb "s3://$bucket"
        done
        echo -e "${GREEN}✅ SAM buckets deleted${NC}"
    fi
fi

echo -e "\n${GREEN}✅ Teardown complete!${NC}"
echo -e "${GREEN}All AWS resources for the payment API have been removed.${NC}"