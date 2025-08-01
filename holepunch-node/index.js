const Hyperswarm = require('hyperswarm')
const crypto = require('crypto')
const b4a = require('b4a')
const readline = require('readline')

// Set up stdin/stdout communication
const rl = readline.createInterface({
  input: process.stdin,
  output: process.stdout,
  terminal: false
})

// Send events to Android
function sendEvent(event, data) {
  console.log(`EVENT:${JSON.stringify({ event, data })}`)
}

// Bridge interface
const HolepunchBridge = {
  swarm: null,
  peers: new Map(),
  handlers: {},
  
  initialize() {
    this.swarm = new Hyperswarm()
    
    this.swarm.on('connection', (socket, peerInfo) => {
      const peerId = b4a.toString(peerInfo.publicKey, 'hex')
      
      this.peers.set(peerId, {
        socket,
        info: peerInfo
      })
      
      sendEvent('peer-connected', { peerId })
      
      socket.on('data', (data) => {
        try {
          const message = JSON.parse(data.toString())
          sendEvent('message-received', {
            peerId,
            message
          })
        } catch (e) {
          console.error('Failed to parse message:', e)
        }
      })
      
      socket.on('close', () => {
        this.peers.delete(peerId)
        sendEvent('peer-disconnected', { peerId })
      })
    })
    
    return true
  },
  
  joinTopic(topic) {
    const topicBuffer = crypto.createHash('sha256')
      .update(topic)
      .digest()
    
    const discovery = this.swarm.join(topicBuffer, {
      server: true,
      client: true
    })
    
    discovery.flushed().then(() => {
      sendEvent('topic-joined', { topic })
    })
  },
  
  sendMessage(peerId, message) {
    const peer = this.peers.get(peerId)
    if (peer && peer.socket) {
      peer.socket.write(JSON.stringify(message))
      return true
    }
    return false
  },
  
  destroy() {
    if (this.swarm) {
      this.swarm.destroy()
    }
  }
}

// Process commands from Android
rl.on('line', (line) => {
  try {
    const { cmd, data } = JSON.parse(line)
    
    switch (cmd) {
      case 'init':
        HolepunchBridge.initialize()
        break
      case 'joinTopic':
        HolepunchBridge.joinTopic(data.topic)
        break
      case 'sendMessage':
        HolepunchBridge.sendMessage(data.peerId, data.message)
        break
      case 'destroy':
        HolepunchBridge.destroy()
        process.exit(0)
        break
    }
  } catch (e) {
    console.error('Failed to parse command:', e)
  }
})

console.log('Holepunch Node.js bridge ready')