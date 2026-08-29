#!/bin/sh
# Wait for DynamoDB Local to be ready
echo "Waiting for DynamoDB Local..."
until curl -s http://dynamodb-local:8000 > /dev/null 2>&1; do
  sleep 1
done
echo "DynamoDB Local is up!"

ENDPOINT="http://dynamodb-local:8000"
REGION="us-east-1"

# Create Projects table
aws dynamodb create-table \
  --table-name Projects \
  --attribute-definitions AttributeName=projectId,AttributeType=S \
  --key-schema AttributeName=projectId,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --endpoint-url $ENDPOINT \
  --region $REGION 2>/dev/null || echo "Projects table already exists"

# Create Inventory table
aws dynamodb create-table \
  --table-name Inventory \
  --attribute-definitions \
    AttributeName=projectId,AttributeType=S \
    AttributeName=itemId,AttributeType=S \
  --key-schema \
    AttributeName=projectId,KeyType=HASH \
    AttributeName=itemId,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST \
  --endpoint-url $ENDPOINT \
  --region $REGION 2>/dev/null || echo "Inventory table already exists"

# Create Financials table
aws dynamodb create-table \
  --table-name Financials \
  --attribute-definitions \
    AttributeName=projectId,AttributeType=S \
    AttributeName=recordId,AttributeType=S \
  --key-schema \
    AttributeName=projectId,KeyType=HASH \
    AttributeName=recordId,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST \
  --endpoint-url $ENDPOINT \
  --region $REGION 2>/dev/null || echo "Financials table already exists"

echo "DynamoDB tables ready."

# Create ProjectFiles table  (projectId PK  +  fileId SK)
aws dynamodb create-table \
  --table-name ProjectFiles \
  --attribute-definitions \
    AttributeName=projectId,AttributeType=S \
    AttributeName=fileId,AttributeType=S \
  --key-schema \
    AttributeName=projectId,KeyType=HASH \
    AttributeName=fileId,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST \
  --endpoint-url $ENDPOINT \
  --region $REGION 2>/dev/null || echo "ProjectFiles table already exists"

echo "All tables ready."

