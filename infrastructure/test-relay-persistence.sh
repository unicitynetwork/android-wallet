#!/bin/bash

# Test Nostr relay persistence with PostgreSQL

RELAY_URL="${1:-ws://unicity-nostr-relay-20250927-alb-1919039002.me-central-1.elb.amazonaws.com:8080}"
REGION="${2:-me-central-1}"
STACK_NAME="${3:-unicity-nostr-relay-20250927}"

echo "🧪 Testing Nostr relay persistence..."
echo "   Relay: $RELAY_URL"
echo ""

# Check if websocat is installed
if ! command -v websocat &> /dev/null; then
    echo "❌ websocat is required. Install with: brew install websocat"
    exit 1
fi

# Generate test event
EVENT_ID=$(openssl rand -hex 32)
PUBKEY=$(openssl rand -hex 32)
TIMESTAMP=$(date +%s)
SIGNATURE=$(openssl rand -hex 64)

echo "1️⃣ Publishing test event..."
echo "   Event ID: ${EVENT_ID:0:16}..."

# Create test event (kind 31337 - custom test event)
cat > /tmp/test-event.json << EOF
["EVENT", {
  "id": "$EVENT_ID",
  "pubkey": "$PUBKEY",
  "created_at": $TIMESTAMP,
  "kind": 31337,
  "tags": [["test", "persistence"], ["timestamp", "$TIMESTAMP"]],
  "content": "Test persistence at $(date)",
  "sig": "$SIGNATURE"
}]
EOF

# Send event
echo "$(</tmp/test-event.json)" | websocat -t -1 "$RELAY_URL" &>/dev/null

sleep 2

echo "2️⃣ Querying for event..."

# Query for the event
cat > /tmp/query.json << EOF
["REQ", "test-sub-$TIMESTAMP", {
  "ids": ["$EVENT_ID"],
  "limit": 1
}]
EOF

RESPONSE=$(echo "$(</tmp/query.json)" | websocat -t -1 "$RELAY_URL" 2>/dev/null | head -1)

if [[ "$RESPONSE" == *"$EVENT_ID"* ]]; then
    echo "   ✅ Event found in relay!"
else
    echo "   ❌ Event not found"
    exit 1
fi

# Optional: Restart ECS tasks to test persistence
if [ "$4" == "--restart" ]; then
    echo ""
    echo "3️⃣ Restarting ECS tasks to test persistence..."

    # Get current tasks
    CLUSTER="$STACK_NAME-cluster"
    SERVICE="$STACK_NAME-nostr-relay"

    TASKS=$(aws ecs list-tasks \
        --cluster $CLUSTER \
        --service-name $SERVICE \
        --region $REGION \
        --query 'taskArns[]' \
        --output text 2>/dev/null)

    if [ -z "$TASKS" ]; then
        echo "   ⚠️  No tasks found. Skipping restart test."
    else
        # Stop tasks (ECS will auto-restart)
        for TASK in $TASKS; do
            TASK_ID=$(echo $TASK | rev | cut -d'/' -f1 | rev)
            echo "   Stopping task: ${TASK_ID:0:8}..."
            aws ecs stop-task \
                --cluster $CLUSTER \
                --task $TASK \
                --region $REGION > /dev/null
        done

        echo "   ⏳ Waiting 30 seconds for tasks to restart..."
        sleep 30

        echo ""
        echo "4️⃣ Querying after restart..."
        RESPONSE_AFTER=$(echo "$(</tmp/query.json)" | websocat -t -1 "$RELAY_URL" 2>/dev/null | head -1)

        if [[ "$RESPONSE_AFTER" == *"$EVENT_ID"* ]]; then
            echo "   ✅ Event persisted across restart!"
        else
            echo "   ❌ Event lost after restart"
            exit 1
        fi
    fi
fi

echo ""
echo "5️⃣ Testing replaceable events (for nametag bindings)..."

# Create replaceable event (kind 30078 - application-specific data)
REPLACEABLE_ID=$(openssl rand -hex 32)
cat > /tmp/replaceable.json << EOF
["EVENT", {
  "id": "$REPLACEABLE_ID",
  "pubkey": "$PUBKEY",
  "created_at": $TIMESTAMP,
  "kind": 30078,
  "tags": [["d", "unicity-nametag"], ["nametag", "test-user"]],
  "content": "{\"nametag\": \"test-user\", \"address\": \"0xtest\"}",
  "sig": "$SIGNATURE"
}]
EOF

echo "$(</tmp/replaceable.json)" | websocat -t -1 "$RELAY_URL" &>/dev/null
sleep 1

# Query for replaceable event
cat > /tmp/query-replaceable.json << EOF
["REQ", "test-replaceable", {
  "kinds": [30078],
  "authors": ["$PUBKEY"],
  "#d": ["unicity-nametag"],
  "limit": 1
}]
EOF

REPLACEABLE_RESPONSE=$(echo "$(</tmp/query-replaceable.json)" | websocat -t -1 "$RELAY_URL" 2>/dev/null | head -1)

if [[ "$REPLACEABLE_RESPONSE" == *"unicity-nametag"* ]]; then
    echo "   ✅ Replaceable event stored!"
else
    echo "   ⚠️  Replaceable event not found"
fi

# Cleanup
rm -f /tmp/test-event.json /tmp/query.json /tmp/replaceable.json /tmp/query-replaceable.json

echo ""
echo "✅ Persistence test complete!"
echo ""
echo "💡 To test with container restart:"
echo "   $0 $RELAY_URL $REGION $STACK_NAME --restart"