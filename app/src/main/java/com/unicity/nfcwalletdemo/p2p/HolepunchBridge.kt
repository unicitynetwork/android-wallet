package com.unicity.nfcwalletdemo.p2p

import android.content.Context

/**
 * Bridge to real Holepunch/Hyperswarm implementation
 * This delegates to RealHolepunchBridge which runs actual Hyperswarm in Node.js
 */
class HolepunchBridge(context: Context) : RealHolepunchBridge(context)