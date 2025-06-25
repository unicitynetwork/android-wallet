(function webpackUniversalModuleDefinition(root, factory) {
	if(typeof exports === 'object' && typeof module === 'object')
		module.exports = factory();
	else if(typeof define === 'function' && define.amd)
		define([], factory);
	else if(typeof exports === 'object')
		exports["unicity"] = factory();
	else
		root["unicity"] = factory();
})(self, () => {
return /******/ (() => { // webpackBootstrap
/******/ 	"use strict";
/******/ 	var __webpack_modules__ = ({

/***/ "./node_modules/@noble/curves/esm/_shortw_utils.js":
/*!*********************************************************!*\
  !*** ./node_modules/@noble/curves/esm/_shortw_utils.js ***!
  \*********************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   createCurve: () => (/* binding */ createCurve),
/* harmony export */   getHash: () => (/* binding */ getHash)
/* harmony export */ });
/* harmony import */ var _noble_hashes_hmac__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @noble/hashes/hmac */ "./node_modules/@noble/hashes/esm/hmac.js");
/* harmony import */ var _noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @noble/hashes/utils */ "./node_modules/@noble/hashes/esm/utils.js");
/* harmony import */ var _abstract_weierstrass_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ./abstract/weierstrass.js */ "./node_modules/@noble/curves/esm/abstract/weierstrass.js");
/**
 * Utilities for short weierstrass curves, combined with noble-hashes.
 * @module
 */
/*! noble-curves - MIT License (c) 2022 Paul Miller (paulmillr.com) */



/** connects noble-curves to noble-hashes */
function getHash(hash) {
    return {
        hash,
        hmac: (key, ...msgs) => (0,_noble_hashes_hmac__WEBPACK_IMPORTED_MODULE_0__.hmac)(hash, key, (0,_noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__.concatBytes)(...msgs)),
        randomBytes: _noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__.randomBytes,
    };
}
function createCurve(curveDef, defHash) {
    const create = (hash) => (0,_abstract_weierstrass_js__WEBPACK_IMPORTED_MODULE_2__.weierstrass)({ ...curveDef, ...getHash(hash) });
    return { ...create(defHash), create };
}
//# sourceMappingURL=_shortw_utils.js.map

/***/ }),

/***/ "./node_modules/@noble/curves/esm/abstract/curve.js":
/*!**********************************************************!*\
  !*** ./node_modules/@noble/curves/esm/abstract/curve.js ***!
  \**********************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   pippenger: () => (/* binding */ pippenger),
/* harmony export */   precomputeMSMUnsafe: () => (/* binding */ precomputeMSMUnsafe),
/* harmony export */   validateBasic: () => (/* binding */ validateBasic),
/* harmony export */   wNAF: () => (/* binding */ wNAF)
/* harmony export */ });
/* harmony import */ var _modular_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ./modular.js */ "./node_modules/@noble/curves/esm/abstract/modular.js");
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./utils.js */ "./node_modules/@noble/curves/esm/abstract/utils.js");
/**
 * Methods for elliptic curve multiplication by scalars.
 * Contains wNAF, pippenger
 * @module
 */
/*! noble-curves - MIT License (c) 2022 Paul Miller (paulmillr.com) */


const _0n = BigInt(0);
const _1n = BigInt(1);
function constTimeNegate(condition, item) {
    const neg = item.negate();
    return condition ? neg : item;
}
function validateW(W, bits) {
    if (!Number.isSafeInteger(W) || W <= 0 || W > bits)
        throw new Error('invalid window size, expected [1..' + bits + '], got W=' + W);
}
function calcWOpts(W, scalarBits) {
    validateW(W, scalarBits);
    const windows = Math.ceil(scalarBits / W) + 1; // W=8 33. Not 32, because we skip zero
    const windowSize = 2 ** (W - 1); // W=8 128. Not 256, because we skip zero
    const maxNumber = 2 ** W; // W=8 256
    const mask = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bitMask)(W); // W=8 255 == mask 0b11111111
    const shiftBy = BigInt(W); // W=8 8
    return { windows, windowSize, mask, maxNumber, shiftBy };
}
function calcOffsets(n, window, wOpts) {
    const { windowSize, mask, maxNumber, shiftBy } = wOpts;
    let wbits = Number(n & mask); // extract W bits.
    let nextN = n >> shiftBy; // shift number by W bits.
    // What actually happens here:
    // const highestBit = Number(mask ^ (mask >> 1n));
    // let wbits2 = wbits - 1; // skip zero
    // if (wbits2 & highestBit) { wbits2 ^= Number(mask); // (~);
    // split if bits > max: +224 => 256-32
    if (wbits > windowSize) {
        // we skip zero, which means instead of `>= size-1`, we do `> size`
        wbits -= maxNumber; // -32, can be maxNumber - wbits, but then we need to set isNeg here.
        nextN += _1n; // +256 (carry)
    }
    const offsetStart = window * windowSize;
    const offset = offsetStart + Math.abs(wbits) - 1; // -1 because we skip zero
    const isZero = wbits === 0; // is current window slice a 0?
    const isNeg = wbits < 0; // is current window slice negative?
    const isNegF = window % 2 !== 0; // fake random statement for noise
    const offsetF = offsetStart; // fake offset for noise
    return { nextN, offset, isZero, isNeg, isNegF, offsetF };
}
function validateMSMPoints(points, c) {
    if (!Array.isArray(points))
        throw new Error('array expected');
    points.forEach((p, i) => {
        if (!(p instanceof c))
            throw new Error('invalid point at index ' + i);
    });
}
function validateMSMScalars(scalars, field) {
    if (!Array.isArray(scalars))
        throw new Error('array of scalars expected');
    scalars.forEach((s, i) => {
        if (!field.isValid(s))
            throw new Error('invalid scalar at index ' + i);
    });
}
// Since points in different groups cannot be equal (different object constructor),
// we can have single place to store precomputes.
// Allows to make points frozen / immutable.
const pointPrecomputes = new WeakMap();
const pointWindowSizes = new WeakMap();
function getW(P) {
    return pointWindowSizes.get(P) || 1;
}
/**
 * Elliptic curve multiplication of Point by scalar. Fragile.
 * Scalars should always be less than curve order: this should be checked inside of a curve itself.
 * Creates precomputation tables for fast multiplication:
 * - private scalar is split by fixed size windows of W bits
 * - every window point is collected from window's table & added to accumulator
 * - since windows are different, same point inside tables won't be accessed more than once per calc
 * - each multiplication is 'Math.ceil(CURVE_ORDER / 𝑊) + 1' point additions (fixed for any scalar)
 * - +1 window is neccessary for wNAF
 * - wNAF reduces table size: 2x less memory + 2x faster generation, but 10% slower multiplication
 *
 * @todo Research returning 2d JS array of windows, instead of a single window.
 * This would allow windows to be in different memory locations
 */
function wNAF(c, bits) {
    return {
        constTimeNegate,
        hasPrecomputes(elm) {
            return getW(elm) !== 1;
        },
        // non-const time multiplication ladder
        unsafeLadder(elm, n, p = c.ZERO) {
            let d = elm;
            while (n > _0n) {
                if (n & _1n)
                    p = p.add(d);
                d = d.double();
                n >>= _1n;
            }
            return p;
        },
        /**
         * Creates a wNAF precomputation window. Used for caching.
         * Default window size is set by `utils.precompute()` and is equal to 8.
         * Number of precomputed points depends on the curve size:
         * 2^(𝑊−1) * (Math.ceil(𝑛 / 𝑊) + 1), where:
         * - 𝑊 is the window size
         * - 𝑛 is the bitlength of the curve order.
         * For a 256-bit curve and window size 8, the number of precomputed points is 128 * 33 = 4224.
         * @param elm Point instance
         * @param W window size
         * @returns precomputed point tables flattened to a single array
         */
        precomputeWindow(elm, W) {
            const { windows, windowSize } = calcWOpts(W, bits);
            const points = [];
            let p = elm;
            let base = p;
            for (let window = 0; window < windows; window++) {
                base = p;
                points.push(base);
                // i=1, bc we skip 0
                for (let i = 1; i < windowSize; i++) {
                    base = base.add(p);
                    points.push(base);
                }
                p = base.double();
            }
            return points;
        },
        /**
         * Implements ec multiplication using precomputed tables and w-ary non-adjacent form.
         * @param W window size
         * @param precomputes precomputed tables
         * @param n scalar (we don't check here, but should be less than curve order)
         * @returns real and fake (for const-time) points
         */
        wNAF(W, precomputes, n) {
            // Smaller version:
            // https://github.com/paulmillr/noble-secp256k1/blob/47cb1669b6e506ad66b35fe7d76132ae97465da2/index.ts#L502-L541
            // TODO: check the scalar is less than group order?
            // wNAF behavior is undefined otherwise. But have to carefully remove
            // other checks before wNAF. ORDER == bits here.
            // Accumulators
            let p = c.ZERO;
            let f = c.BASE;
            // This code was first written with assumption that 'f' and 'p' will never be infinity point:
            // since each addition is multiplied by 2 ** W, it cannot cancel each other. However,
            // there is negate now: it is possible that negated element from low value
            // would be the same as high element, which will create carry into next window.
            // It's not obvious how this can fail, but still worth investigating later.
            const wo = calcWOpts(W, bits);
            for (let window = 0; window < wo.windows; window++) {
                // (n === _0n) is handled and not early-exited. isEven and offsetF are used for noise
                const { nextN, offset, isZero, isNeg, isNegF, offsetF } = calcOffsets(n, window, wo);
                n = nextN;
                if (isZero) {
                    // bits are 0: add garbage to fake point
                    // Important part for const-time getPublicKey: add random "noise" point to f.
                    f = f.add(constTimeNegate(isNegF, precomputes[offsetF]));
                }
                else {
                    // bits are 1: add to result point
                    p = p.add(constTimeNegate(isNeg, precomputes[offset]));
                }
            }
            // Return both real and fake points: JIT won't eliminate f.
            // At this point there is a way to F be infinity-point even if p is not,
            // which makes it less const-time: around 1 bigint multiply.
            return { p, f };
        },
        /**
         * Implements ec unsafe (non const-time) multiplication using precomputed tables and w-ary non-adjacent form.
         * @param W window size
         * @param precomputes precomputed tables
         * @param n scalar (we don't check here, but should be less than curve order)
         * @param acc accumulator point to add result of multiplication
         * @returns point
         */
        wNAFUnsafe(W, precomputes, n, acc = c.ZERO) {
            const wo = calcWOpts(W, bits);
            for (let window = 0; window < wo.windows; window++) {
                if (n === _0n)
                    break; // Early-exit, skip 0 value
                const { nextN, offset, isZero, isNeg } = calcOffsets(n, window, wo);
                n = nextN;
                if (isZero) {
                    // Window bits are 0: skip processing.
                    // Move to next window.
                    continue;
                }
                else {
                    const item = precomputes[offset];
                    acc = acc.add(isNeg ? item.negate() : item); // Re-using acc allows to save adds in MSM
                }
            }
            return acc;
        },
        getPrecomputes(W, P, transform) {
            // Calculate precomputes on a first run, reuse them after
            let comp = pointPrecomputes.get(P);
            if (!comp) {
                comp = this.precomputeWindow(P, W);
                if (W !== 1)
                    pointPrecomputes.set(P, transform(comp));
            }
            return comp;
        },
        wNAFCached(P, n, transform) {
            const W = getW(P);
            return this.wNAF(W, this.getPrecomputes(W, P, transform), n);
        },
        wNAFCachedUnsafe(P, n, transform, prev) {
            const W = getW(P);
            if (W === 1)
                return this.unsafeLadder(P, n, prev); // For W=1 ladder is ~x2 faster
            return this.wNAFUnsafe(W, this.getPrecomputes(W, P, transform), n, prev);
        },
        // We calculate precomputes for elliptic curve point multiplication
        // using windowed method. This specifies window size and
        // stores precomputed values. Usually only base point would be precomputed.
        setWindowSize(P, W) {
            validateW(W, bits);
            pointWindowSizes.set(P, W);
            pointPrecomputes.delete(P);
        },
    };
}
/**
 * Pippenger algorithm for multi-scalar multiplication (MSM, Pa + Qb + Rc + ...).
 * 30x faster vs naive addition on L=4096, 10x faster than precomputes.
 * For N=254bit, L=1, it does: 1024 ADD + 254 DBL. For L=5: 1536 ADD + 254 DBL.
 * Algorithmically constant-time (for same L), even when 1 point + scalar, or when scalar = 0.
 * @param c Curve Point constructor
 * @param fieldN field over CURVE.N - important that it's not over CURVE.P
 * @param points array of L curve points
 * @param scalars array of L scalars (aka private keys / bigints)
 */
function pippenger(c, fieldN, points, scalars) {
    // If we split scalars by some window (let's say 8 bits), every chunk will only
    // take 256 buckets even if there are 4096 scalars, also re-uses double.
    // TODO:
    // - https://eprint.iacr.org/2024/750.pdf
    // - https://tches.iacr.org/index.php/TCHES/article/view/10287
    // 0 is accepted in scalars
    validateMSMPoints(points, c);
    validateMSMScalars(scalars, fieldN);
    const plength = points.length;
    const slength = scalars.length;
    if (plength !== slength)
        throw new Error('arrays of points and scalars must have equal length');
    // if (plength === 0) throw new Error('array must be of length >= 2');
    const zero = c.ZERO;
    const wbits = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bitLen)(BigInt(plength));
    let windowSize = 1; // bits
    if (wbits > 12)
        windowSize = wbits - 3;
    else if (wbits > 4)
        windowSize = wbits - 2;
    else if (wbits > 0)
        windowSize = 2;
    const MASK = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bitMask)(windowSize);
    const buckets = new Array(Number(MASK) + 1).fill(zero); // +1 for zero array
    const lastBits = Math.floor((fieldN.BITS - 1) / windowSize) * windowSize;
    let sum = zero;
    for (let i = lastBits; i >= 0; i -= windowSize) {
        buckets.fill(zero);
        for (let j = 0; j < slength; j++) {
            const scalar = scalars[j];
            const wbits = Number((scalar >> BigInt(i)) & MASK);
            buckets[wbits] = buckets[wbits].add(points[j]);
        }
        let resI = zero; // not using this will do small speed-up, but will lose ct
        // Skip first bucket, because it is zero
        for (let j = buckets.length - 1, sumI = zero; j > 0; j--) {
            sumI = sumI.add(buckets[j]);
            resI = resI.add(sumI);
        }
        sum = sum.add(resI);
        if (i !== 0)
            for (let j = 0; j < windowSize; j++)
                sum = sum.double();
    }
    return sum;
}
/**
 * Precomputed multi-scalar multiplication (MSM, Pa + Qb + Rc + ...).
 * @param c Curve Point constructor
 * @param fieldN field over CURVE.N - important that it's not over CURVE.P
 * @param points array of L curve points
 * @returns function which multiplies points with scaars
 */
function precomputeMSMUnsafe(c, fieldN, points, windowSize) {
    /**
     * Performance Analysis of Window-based Precomputation
     *
     * Base Case (256-bit scalar, 8-bit window):
     * - Standard precomputation requires:
     *   - 31 additions per scalar × 256 scalars = 7,936 ops
     *   - Plus 255 summary additions = 8,191 total ops
     *   Note: Summary additions can be optimized via accumulator
     *
     * Chunked Precomputation Analysis:
     * - Using 32 chunks requires:
     *   - 255 additions per chunk
     *   - 256 doublings
     *   - Total: (255 × 32) + 256 = 8,416 ops
     *
     * Memory Usage Comparison:
     * Window Size | Standard Points | Chunked Points
     * ------------|-----------------|---------------
     *     4-bit   |     520         |      15
     *     8-bit   |    4,224        |     255
     *    10-bit   |   13,824        |   1,023
     *    16-bit   |  557,056        |  65,535
     *
     * Key Advantages:
     * 1. Enables larger window sizes due to reduced memory overhead
     * 2. More efficient for smaller scalar counts:
     *    - 16 chunks: (16 × 255) + 256 = 4,336 ops
     *    - ~2x faster than standard 8,191 ops
     *
     * Limitations:
     * - Not suitable for plain precomputes (requires 256 constant doublings)
     * - Performance degrades with larger scalar counts:
     *   - Optimal for ~256 scalars
     *   - Less efficient for 4096+ scalars (Pippenger preferred)
     */
    validateW(windowSize, fieldN.BITS);
    validateMSMPoints(points, c);
    const zero = c.ZERO;
    const tableSize = 2 ** windowSize - 1; // table size (without zero)
    const chunks = Math.ceil(fieldN.BITS / windowSize); // chunks of item
    const MASK = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bitMask)(windowSize);
    const tables = points.map((p) => {
        const res = [];
        for (let i = 0, acc = p; i < tableSize; i++) {
            res.push(acc);
            acc = acc.add(p);
        }
        return res;
    });
    return (scalars) => {
        validateMSMScalars(scalars, fieldN);
        if (scalars.length > points.length)
            throw new Error('array of scalars must be smaller than array of points');
        let res = zero;
        for (let i = 0; i < chunks; i++) {
            // No need to double if accumulator is still zero.
            if (res !== zero)
                for (let j = 0; j < windowSize; j++)
                    res = res.double();
            const shiftBy = BigInt(chunks * windowSize - (i + 1) * windowSize);
            for (let j = 0; j < scalars.length; j++) {
                const n = scalars[j];
                const curr = Number((n >> shiftBy) & MASK);
                if (!curr)
                    continue; // skip zero scalars chunks
                res = res.add(tables[j][curr - 1]);
            }
        }
        return res;
    };
}
function validateBasic(curve) {
    (0,_modular_js__WEBPACK_IMPORTED_MODULE_1__.validateField)(curve.Fp);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.validateObject)(curve, {
        n: 'bigint',
        h: 'bigint',
        Gx: 'field',
        Gy: 'field',
    }, {
        nBitLength: 'isSafeInteger',
        nByteLength: 'isSafeInteger',
    });
    // Set defaults
    return Object.freeze({
        ...(0,_modular_js__WEBPACK_IMPORTED_MODULE_1__.nLength)(curve.n, curve.nBitLength),
        ...curve,
        ...{ p: curve.Fp.ORDER },
    });
}
//# sourceMappingURL=curve.js.map

/***/ }),

/***/ "./node_modules/@noble/curves/esm/abstract/hash-to-curve.js":
/*!******************************************************************!*\
  !*** ./node_modules/@noble/curves/esm/abstract/hash-to-curve.js ***!
  \******************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   createHasher: () => (/* binding */ createHasher),
/* harmony export */   expand_message_xmd: () => (/* binding */ expand_message_xmd),
/* harmony export */   expand_message_xof: () => (/* binding */ expand_message_xof),
/* harmony export */   hash_to_field: () => (/* binding */ hash_to_field),
/* harmony export */   isogenyMap: () => (/* binding */ isogenyMap)
/* harmony export */ });
/* harmony import */ var _modular_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ./modular.js */ "./node_modules/@noble/curves/esm/abstract/modular.js");
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./utils.js */ "./node_modules/@noble/curves/esm/abstract/utils.js");


// Octet Stream to Integer. "spec" implementation of os2ip is 2.5x slower vs bytesToNumberBE.
const os2ip = _utils_js__WEBPACK_IMPORTED_MODULE_0__.bytesToNumberBE;
// Integer to Octet Stream (numberToBytesBE)
function i2osp(value, length) {
    anum(value);
    anum(length);
    if (value < 0 || value >= 1 << (8 * length))
        throw new Error('invalid I2OSP input: ' + value);
    const res = Array.from({ length }).fill(0);
    for (let i = length - 1; i >= 0; i--) {
        res[i] = value & 0xff;
        value >>>= 8;
    }
    return new Uint8Array(res);
}
function strxor(a, b) {
    const arr = new Uint8Array(a.length);
    for (let i = 0; i < a.length; i++) {
        arr[i] = a[i] ^ b[i];
    }
    return arr;
}
function anum(item) {
    if (!Number.isSafeInteger(item))
        throw new Error('number expected');
}
/**
 * Produces a uniformly random byte string using a cryptographic hash function H that outputs b bits.
 * [RFC 9380 5.3.1](https://www.rfc-editor.org/rfc/rfc9380#section-5.3.1).
 */
function expand_message_xmd(msg, DST, lenInBytes, H) {
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abytes)(msg);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abytes)(DST);
    anum(lenInBytes);
    // https://www.rfc-editor.org/rfc/rfc9380#section-5.3.3
    if (DST.length > 255)
        DST = H((0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.concatBytes)((0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.utf8ToBytes)('H2C-OVERSIZE-DST-'), DST));
    const { outputLen: b_in_bytes, blockLen: r_in_bytes } = H;
    const ell = Math.ceil(lenInBytes / b_in_bytes);
    if (lenInBytes > 65535 || ell > 255)
        throw new Error('expand_message_xmd: invalid lenInBytes');
    const DST_prime = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.concatBytes)(DST, i2osp(DST.length, 1));
    const Z_pad = i2osp(0, r_in_bytes);
    const l_i_b_str = i2osp(lenInBytes, 2); // len_in_bytes_str
    const b = new Array(ell);
    const b_0 = H((0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.concatBytes)(Z_pad, msg, l_i_b_str, i2osp(0, 1), DST_prime));
    b[0] = H((0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.concatBytes)(b_0, i2osp(1, 1), DST_prime));
    for (let i = 1; i <= ell; i++) {
        const args = [strxor(b_0, b[i - 1]), i2osp(i + 1, 1), DST_prime];
        b[i] = H((0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.concatBytes)(...args));
    }
    const pseudo_random_bytes = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.concatBytes)(...b);
    return pseudo_random_bytes.slice(0, lenInBytes);
}
/**
 * Produces a uniformly random byte string using an extendable-output function (XOF) H.
 * 1. The collision resistance of H MUST be at least k bits.
 * 2. H MUST be an XOF that has been proved indifferentiable from
 *    a random oracle under a reasonable cryptographic assumption.
 * [RFC 9380 5.3.2](https://www.rfc-editor.org/rfc/rfc9380#section-5.3.2).
 */
function expand_message_xof(msg, DST, lenInBytes, k, H) {
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abytes)(msg);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abytes)(DST);
    anum(lenInBytes);
    // https://www.rfc-editor.org/rfc/rfc9380#section-5.3.3
    // DST = H('H2C-OVERSIZE-DST-' || a_very_long_DST, Math.ceil((lenInBytes * k) / 8));
    if (DST.length > 255) {
        const dkLen = Math.ceil((2 * k) / 8);
        DST = H.create({ dkLen }).update((0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.utf8ToBytes)('H2C-OVERSIZE-DST-')).update(DST).digest();
    }
    if (lenInBytes > 65535 || DST.length > 255)
        throw new Error('expand_message_xof: invalid lenInBytes');
    return (H.create({ dkLen: lenInBytes })
        .update(msg)
        .update(i2osp(lenInBytes, 2))
        // 2. DST_prime = DST || I2OSP(len(DST), 1)
        .update(DST)
        .update(i2osp(DST.length, 1))
        .digest());
}
/**
 * Hashes arbitrary-length byte strings to a list of one or more elements of a finite field F.
 * [RFC 9380 5.2](https://www.rfc-editor.org/rfc/rfc9380#section-5.2).
 * @param msg a byte string containing the message to hash
 * @param count the number of elements of F to output
 * @param options `{DST: string, p: bigint, m: number, k: number, expand: 'xmd' | 'xof', hash: H}`, see above
 * @returns [u_0, ..., u_(count - 1)], a list of field elements.
 */
function hash_to_field(msg, count, options) {
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.validateObject)(options, {
        DST: 'stringOrUint8Array',
        p: 'bigint',
        m: 'isSafeInteger',
        k: 'isSafeInteger',
        hash: 'hash',
    });
    const { p, k, m, hash, expand, DST: _DST } = options;
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abytes)(msg);
    anum(count);
    const DST = typeof _DST === 'string' ? (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.utf8ToBytes)(_DST) : _DST;
    const log2p = p.toString(2).length;
    const L = Math.ceil((log2p + k) / 8); // section 5.1 of ietf draft link above
    const len_in_bytes = count * m * L;
    let prb; // pseudo_random_bytes
    if (expand === 'xmd') {
        prb = expand_message_xmd(msg, DST, len_in_bytes, hash);
    }
    else if (expand === 'xof') {
        prb = expand_message_xof(msg, DST, len_in_bytes, k, hash);
    }
    else if (expand === '_internal_pass') {
        // for internal tests only
        prb = msg;
    }
    else {
        throw new Error('expand must be "xmd" or "xof"');
    }
    const u = new Array(count);
    for (let i = 0; i < count; i++) {
        const e = new Array(m);
        for (let j = 0; j < m; j++) {
            const elm_offset = L * (j + i * m);
            const tv = prb.subarray(elm_offset, elm_offset + L);
            e[j] = (0,_modular_js__WEBPACK_IMPORTED_MODULE_1__.mod)(os2ip(tv), p);
        }
        u[i] = e;
    }
    return u;
}
function isogenyMap(field, map) {
    // Make same order as in spec
    const coeff = map.map((i) => Array.from(i).reverse());
    return (x, y) => {
        const [xn, xd, yn, yd] = coeff.map((val) => val.reduce((acc, i) => field.add(field.mul(acc, x), i)));
        // 6.6.3
        // Exceptional cases of iso_map are inputs that cause the denominator of
        // either rational function to evaluate to zero; such cases MUST return
        // the identity point on E.
        const [xd_inv, yd_inv] = (0,_modular_js__WEBPACK_IMPORTED_MODULE_1__.FpInvertBatch)(field, [xd, yd], true);
        x = field.mul(xn, xd_inv); // xNum / xDen
        y = field.mul(y, field.mul(yn, yd_inv)); // y * (yNum / yDev)
        return { x, y };
    };
}
/** Creates hash-to-curve methods from EC Point and mapToCurve function. */
function createHasher(Point, mapToCurve, defaults) {
    if (typeof mapToCurve !== 'function')
        throw new Error('mapToCurve() must be defined');
    function map(num) {
        return Point.fromAffine(mapToCurve(num));
    }
    function clear(initial) {
        const P = initial.clearCofactor();
        if (P.equals(Point.ZERO))
            return Point.ZERO; // zero will throw in assert
        P.assertValidity();
        return P;
    }
    return {
        defaults,
        // Encodes byte string to elliptic curve.
        // hash_to_curve from https://www.rfc-editor.org/rfc/rfc9380#section-3
        hashToCurve(msg, options) {
            const u = hash_to_field(msg, 2, { ...defaults, DST: defaults.DST, ...options });
            const u0 = map(u[0]);
            const u1 = map(u[1]);
            return clear(u0.add(u1));
        },
        // Encodes byte string to elliptic curve.
        // encode_to_curve from https://www.rfc-editor.org/rfc/rfc9380#section-3
        encodeToCurve(msg, options) {
            const u = hash_to_field(msg, 1, { ...defaults, DST: defaults.encodeDST, ...options });
            return clear(map(u[0]));
        },
        // Same as encodeToCurve, but without hash
        mapToCurve(scalars) {
            if (!Array.isArray(scalars))
                throw new Error('expected array of bigints');
            for (const i of scalars)
                if (typeof i !== 'bigint')
                    throw new Error('expected array of bigints');
            return clear(map(scalars));
        },
    };
}
//# sourceMappingURL=hash-to-curve.js.map

/***/ }),

/***/ "./node_modules/@noble/curves/esm/abstract/modular.js":
/*!************************************************************!*\
  !*** ./node_modules/@noble/curves/esm/abstract/modular.js ***!
  \************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   Field: () => (/* binding */ Field),
/* harmony export */   FpDiv: () => (/* binding */ FpDiv),
/* harmony export */   FpInvertBatch: () => (/* binding */ FpInvertBatch),
/* harmony export */   FpIsSquare: () => (/* binding */ FpIsSquare),
/* harmony export */   FpLegendre: () => (/* binding */ FpLegendre),
/* harmony export */   FpPow: () => (/* binding */ FpPow),
/* harmony export */   FpSqrt: () => (/* binding */ FpSqrt),
/* harmony export */   FpSqrtEven: () => (/* binding */ FpSqrtEven),
/* harmony export */   FpSqrtOdd: () => (/* binding */ FpSqrtOdd),
/* harmony export */   getFieldBytesLength: () => (/* binding */ getFieldBytesLength),
/* harmony export */   getMinHashLength: () => (/* binding */ getMinHashLength),
/* harmony export */   hashToPrivateScalar: () => (/* binding */ hashToPrivateScalar),
/* harmony export */   invert: () => (/* binding */ invert),
/* harmony export */   isNegativeLE: () => (/* binding */ isNegativeLE),
/* harmony export */   mapHashToField: () => (/* binding */ mapHashToField),
/* harmony export */   mod: () => (/* binding */ mod),
/* harmony export */   nLength: () => (/* binding */ nLength),
/* harmony export */   pow: () => (/* binding */ pow),
/* harmony export */   pow2: () => (/* binding */ pow2),
/* harmony export */   tonelliShanks: () => (/* binding */ tonelliShanks),
/* harmony export */   validateField: () => (/* binding */ validateField)
/* harmony export */ });
/* harmony import */ var _noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @noble/hashes/utils */ "./node_modules/@noble/hashes/esm/utils.js");
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./utils.js */ "./node_modules/@noble/curves/esm/abstract/utils.js");
/**
 * Utils for modular division and finite fields.
 * A finite field over 11 is integer number operations `mod 11`.
 * There is no division: it is replaced by modular multiplicative inverse.
 * @module
 */
/*! noble-curves - MIT License (c) 2022 Paul Miller (paulmillr.com) */


// prettier-ignore
const _0n = BigInt(0), _1n = BigInt(1), _2n = /* @__PURE__ */ BigInt(2), _3n = /* @__PURE__ */ BigInt(3);
// prettier-ignore
const _4n = /* @__PURE__ */ BigInt(4), _5n = /* @__PURE__ */ BigInt(5), _8n = /* @__PURE__ */ BigInt(8);
// Calculates a modulo b
function mod(a, b) {
    const result = a % b;
    return result >= _0n ? result : b + result;
}
/**
 * Efficiently raise num to power and do modular division.
 * Unsafe in some contexts: uses ladder, so can expose bigint bits.
 * TODO: remove.
 * @example
 * pow(2n, 6n, 11n) // 64n % 11n == 9n
 */
function pow(num, power, modulo) {
    return FpPow(Field(modulo), num, power);
}
/** Does `x^(2^power)` mod p. `pow2(30, 4)` == `30^(2^4)` */
function pow2(x, power, modulo) {
    let res = x;
    while (power-- > _0n) {
        res *= res;
        res %= modulo;
    }
    return res;
}
/**
 * Inverses number over modulo.
 * Implemented using [Euclidean GCD](https://brilliant.org/wiki/extended-euclidean-algorithm/).
 */
function invert(number, modulo) {
    if (number === _0n)
        throw new Error('invert: expected non-zero number');
    if (modulo <= _0n)
        throw new Error('invert: expected positive modulus, got ' + modulo);
    // Fermat's little theorem "CT-like" version inv(n) = n^(m-2) mod m is 30x slower.
    let a = mod(number, modulo);
    let b = modulo;
    // prettier-ignore
    let x = _0n, y = _1n, u = _1n, v = _0n;
    while (a !== _0n) {
        // JIT applies optimization if those two lines follow each other
        const q = b / a;
        const r = b % a;
        const m = x - u * q;
        const n = y - v * q;
        // prettier-ignore
        b = a, a = r, x = u, y = v, u = m, v = n;
    }
    const gcd = b;
    if (gcd !== _1n)
        throw new Error('invert: does not exist');
    return mod(x, modulo);
}
// Not all roots are possible! Example which will throw:
// const NUM =
// n = 72057594037927816n;
// Fp = Field(BigInt('0x1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab'));
function sqrt3mod4(Fp, n) {
    const p1div4 = (Fp.ORDER + _1n) / _4n;
    const root = Fp.pow(n, p1div4);
    // Throw if root^2 != n
    if (!Fp.eql(Fp.sqr(root), n))
        throw new Error('Cannot find square root');
    return root;
}
function sqrt5mod8(Fp, n) {
    const p5div8 = (Fp.ORDER - _5n) / _8n;
    const n2 = Fp.mul(n, _2n);
    const v = Fp.pow(n2, p5div8);
    const nv = Fp.mul(n, v);
    const i = Fp.mul(Fp.mul(nv, _2n), v);
    const root = Fp.mul(nv, Fp.sub(i, Fp.ONE));
    if (!Fp.eql(Fp.sqr(root), n))
        throw new Error('Cannot find square root');
    return root;
}
// TODO: Commented-out for now. Provide test vectors.
// Tonelli is too slow for extension fields Fp2.
// That means we can't use sqrt (c1, c2...) even for initialization constants.
// if (P % _16n === _9n) return sqrt9mod16;
// // prettier-ignore
// function sqrt9mod16<T>(Fp: IField<T>, n: T, p7div16?: bigint) {
//   if (p7div16 === undefined) p7div16 = (Fp.ORDER + BigInt(7)) / _16n;
//   const c1 = Fp.sqrt(Fp.neg(Fp.ONE)); //  1. c1 = sqrt(-1) in F, i.e., (c1^2) == -1 in F
//   const c2 = Fp.sqrt(c1);             //  2. c2 = sqrt(c1) in F, i.e., (c2^2) == c1 in F
//   const c3 = Fp.sqrt(Fp.neg(c1));     //  3. c3 = sqrt(-c1) in F, i.e., (c3^2) == -c1 in F
//   const c4 = p7div16;                 //  4. c4 = (q + 7) / 16        # Integer arithmetic
//   let tv1 = Fp.pow(n, c4);            //  1. tv1 = x^c4
//   let tv2 = Fp.mul(c1, tv1);          //  2. tv2 = c1 * tv1
//   const tv3 = Fp.mul(c2, tv1);        //  3. tv3 = c2 * tv1
//   let tv4 = Fp.mul(c3, tv1);          //  4. tv4 = c3 * tv1
//   const e1 = Fp.eql(Fp.sqr(tv2), n);  //  5.  e1 = (tv2^2) == x
//   const e2 = Fp.eql(Fp.sqr(tv3), n);  //  6.  e2 = (tv3^2) == x
//   tv1 = Fp.cmov(tv1, tv2, e1); //  7. tv1 = CMOV(tv1, tv2, e1)  # Select tv2 if (tv2^2) == x
//   tv2 = Fp.cmov(tv4, tv3, e2); //  8. tv2 = CMOV(tv4, tv3, e2)  # Select tv3 if (tv3^2) == x
//   const e3 = Fp.eql(Fp.sqr(tv2), n);  //  9.  e3 = (tv2^2) == x
//   return Fp.cmov(tv1, tv2, e3); // 10.  z = CMOV(tv1, tv2, e3) # Select the sqrt from tv1 and tv2
// }
/**
 * Tonelli-Shanks square root search algorithm.
 * 1. https://eprint.iacr.org/2012/685.pdf (page 12)
 * 2. Square Roots from 1; 24, 51, 10 to Dan Shanks
 * @param P field order
 * @returns function that takes field Fp (created from P) and number n
 */
function tonelliShanks(P) {
    // Initialization (precomputation).
    if (P < BigInt(3))
        throw new Error('sqrt is not defined for small field');
    // Factor P - 1 = Q * 2^S, where Q is odd
    let Q = P - _1n;
    let S = 0;
    while (Q % _2n === _0n) {
        Q /= _2n;
        S++;
    }
    // Find the first quadratic non-residue Z >= 2
    let Z = _2n;
    const _Fp = Field(P);
    while (FpLegendre(_Fp, Z) === 1) {
        // Basic primality test for P. After x iterations, chance of
        // not finding quadratic non-residue is 2^x, so 2^1000.
        if (Z++ > 1000)
            throw new Error('Cannot find square root: probably non-prime P');
    }
    // Fast-path; usually done before Z, but we do "primality test".
    if (S === 1)
        return sqrt3mod4;
    // Slow-path
    // TODO: test on Fp2 and others
    let cc = _Fp.pow(Z, Q); // c = z^Q
    const Q1div2 = (Q + _1n) / _2n;
    return function tonelliSlow(Fp, n) {
        if (Fp.is0(n))
            return n;
        // Check if n is a quadratic residue using Legendre symbol
        if (FpLegendre(Fp, n) !== 1)
            throw new Error('Cannot find square root');
        // Initialize variables for the main loop
        let M = S;
        let c = Fp.mul(Fp.ONE, cc); // c = z^Q, move cc from field _Fp into field Fp
        let t = Fp.pow(n, Q); // t = n^Q, first guess at the fudge factor
        let R = Fp.pow(n, Q1div2); // R = n^((Q+1)/2), first guess at the square root
        // Main loop
        // while t != 1
        while (!Fp.eql(t, Fp.ONE)) {
            if (Fp.is0(t))
                return Fp.ZERO; // if t=0 return R=0
            let i = 1;
            // Find the smallest i >= 1 such that t^(2^i) ≡ 1 (mod P)
            let t_tmp = Fp.sqr(t); // t^(2^1)
            while (!Fp.eql(t_tmp, Fp.ONE)) {
                i++;
                t_tmp = Fp.sqr(t_tmp); // t^(2^2)...
                if (i === M)
                    throw new Error('Cannot find square root');
            }
            // Calculate the exponent for b: 2^(M - i - 1)
            const exponent = _1n << BigInt(M - i - 1); // bigint is important
            const b = Fp.pow(c, exponent); // b = 2^(M - i - 1)
            // Update variables
            M = i;
            c = Fp.sqr(b); // c = b^2
            t = Fp.mul(t, c); // t = (t * b^2)
            R = Fp.mul(R, b); // R = R*b
        }
        return R;
    };
}
/**
 * Square root for a finite field. Will try optimized versions first:
 *
 * 1. P ≡ 3 (mod 4)
 * 2. P ≡ 5 (mod 8)
 * 3. Tonelli-Shanks algorithm
 *
 * Different algorithms can give different roots, it is up to user to decide which one they want.
 * For example there is FpSqrtOdd/FpSqrtEven to choice root based on oddness (used for hash-to-curve).
 */
function FpSqrt(P) {
    // P ≡ 3 (mod 4) => √n = n^((P+1)/4)
    if (P % _4n === _3n)
        return sqrt3mod4;
    // P ≡ 5 (mod 8) => Atkin algorithm, page 10 of https://eprint.iacr.org/2012/685.pdf
    if (P % _8n === _5n)
        return sqrt5mod8;
    // P ≡ 9 (mod 16) not implemented, see above
    // Tonelli-Shanks algorithm
    return tonelliShanks(P);
}
// Little-endian check for first LE bit (last BE bit);
const isNegativeLE = (num, modulo) => (mod(num, modulo) & _1n) === _1n;
// prettier-ignore
const FIELD_FIELDS = [
    'create', 'isValid', 'is0', 'neg', 'inv', 'sqrt', 'sqr',
    'eql', 'add', 'sub', 'mul', 'pow', 'div',
    'addN', 'subN', 'mulN', 'sqrN'
];
function validateField(field) {
    const initial = {
        ORDER: 'bigint',
        MASK: 'bigint',
        BYTES: 'isSafeInteger',
        BITS: 'isSafeInteger',
    };
    const opts = FIELD_FIELDS.reduce((map, val) => {
        map[val] = 'function';
        return map;
    }, initial);
    return (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.validateObject)(field, opts);
}
// Generic field functions
/**
 * Same as `pow` but for Fp: non-constant-time.
 * Unsafe in some contexts: uses ladder, so can expose bigint bits.
 */
function FpPow(Fp, num, power) {
    if (power < _0n)
        throw new Error('invalid exponent, negatives unsupported');
    if (power === _0n)
        return Fp.ONE;
    if (power === _1n)
        return num;
    let p = Fp.ONE;
    let d = num;
    while (power > _0n) {
        if (power & _1n)
            p = Fp.mul(p, d);
        d = Fp.sqr(d);
        power >>= _1n;
    }
    return p;
}
/**
 * Efficiently invert an array of Field elements.
 * Exception-free. Will return `undefined` for 0 elements.
 * @param passZero map 0 to 0 (instead of undefined)
 */
function FpInvertBatch(Fp, nums, passZero = false) {
    const inverted = new Array(nums.length).fill(passZero ? Fp.ZERO : undefined);
    // Walk from first to last, multiply them by each other MOD p
    const multipliedAcc = nums.reduce((acc, num, i) => {
        if (Fp.is0(num))
            return acc;
        inverted[i] = acc;
        return Fp.mul(acc, num);
    }, Fp.ONE);
    // Invert last element
    const invertedAcc = Fp.inv(multipliedAcc);
    // Walk from last to first, multiply them by inverted each other MOD p
    nums.reduceRight((acc, num, i) => {
        if (Fp.is0(num))
            return acc;
        inverted[i] = Fp.mul(acc, inverted[i]);
        return Fp.mul(acc, num);
    }, invertedAcc);
    return inverted;
}
// TODO: remove
function FpDiv(Fp, lhs, rhs) {
    return Fp.mul(lhs, typeof rhs === 'bigint' ? invert(rhs, Fp.ORDER) : Fp.inv(rhs));
}
/**
 * Legendre symbol.
 * Legendre constant is used to calculate Legendre symbol (a | p)
 * which denotes the value of a^((p-1)/2) (mod p).
 *
 * * (a | p) ≡ 1    if a is a square (mod p), quadratic residue
 * * (a | p) ≡ -1   if a is not a square (mod p), quadratic non residue
 * * (a | p) ≡ 0    if a ≡ 0 (mod p)
 */
function FpLegendre(Fp, n) {
    // We can use 3rd argument as optional cache of this value
    // but seems unneeded for now. The operation is very fast.
    const p1mod2 = (Fp.ORDER - _1n) / _2n;
    const powered = Fp.pow(n, p1mod2);
    const yes = Fp.eql(powered, Fp.ONE);
    const zero = Fp.eql(powered, Fp.ZERO);
    const no = Fp.eql(powered, Fp.neg(Fp.ONE));
    if (!yes && !zero && !no)
        throw new Error('invalid Legendre symbol result');
    return yes ? 1 : zero ? 0 : -1;
}
// This function returns True whenever the value x is a square in the field F.
function FpIsSquare(Fp, n) {
    const l = FpLegendre(Fp, n);
    return l === 1;
}
// CURVE.n lengths
function nLength(n, nBitLength) {
    // Bit size, byte size of CURVE.n
    if (nBitLength !== undefined)
        (0,_noble_hashes_utils__WEBPACK_IMPORTED_MODULE_1__.anumber)(nBitLength);
    const _nBitLength = nBitLength !== undefined ? nBitLength : n.toString(2).length;
    const nByteLength = Math.ceil(_nBitLength / 8);
    return { nBitLength: _nBitLength, nByteLength };
}
/**
 * Initializes a finite field over prime.
 * Major performance optimizations:
 * * a) denormalized operations like mulN instead of mul
 * * b) same object shape: never add or remove keys
 * * c) Object.freeze
 * Fragile: always run a benchmark on a change.
 * Security note: operations don't check 'isValid' for all elements for performance reasons,
 * it is caller responsibility to check this.
 * This is low-level code, please make sure you know what you're doing.
 * @param ORDER prime positive bigint
 * @param bitLen how many bits the field consumes
 * @param isLE (def: false) if encoding / decoding should be in little-endian
 * @param redef optional faster redefinitions of sqrt and other methods
 */
function Field(ORDER, bitLen, isLE = false, redef = {}) {
    if (ORDER <= _0n)
        throw new Error('invalid field: expected ORDER > 0, got ' + ORDER);
    const { nBitLength: BITS, nByteLength: BYTES } = nLength(ORDER, bitLen);
    if (BYTES > 2048)
        throw new Error('invalid field: expected ORDER of <= 2048 bytes');
    let sqrtP; // cached sqrtP
    const f = Object.freeze({
        ORDER,
        isLE,
        BITS,
        BYTES,
        MASK: (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bitMask)(BITS),
        ZERO: _0n,
        ONE: _1n,
        create: (num) => mod(num, ORDER),
        isValid: (num) => {
            if (typeof num !== 'bigint')
                throw new Error('invalid field element: expected bigint, got ' + typeof num);
            return _0n <= num && num < ORDER; // 0 is valid element, but it's not invertible
        },
        is0: (num) => num === _0n,
        isOdd: (num) => (num & _1n) === _1n,
        neg: (num) => mod(-num, ORDER),
        eql: (lhs, rhs) => lhs === rhs,
        sqr: (num) => mod(num * num, ORDER),
        add: (lhs, rhs) => mod(lhs + rhs, ORDER),
        sub: (lhs, rhs) => mod(lhs - rhs, ORDER),
        mul: (lhs, rhs) => mod(lhs * rhs, ORDER),
        pow: (num, power) => FpPow(f, num, power),
        div: (lhs, rhs) => mod(lhs * invert(rhs, ORDER), ORDER),
        // Same as above, but doesn't normalize
        sqrN: (num) => num * num,
        addN: (lhs, rhs) => lhs + rhs,
        subN: (lhs, rhs) => lhs - rhs,
        mulN: (lhs, rhs) => lhs * rhs,
        inv: (num) => invert(num, ORDER),
        sqrt: redef.sqrt ||
            ((n) => {
                if (!sqrtP)
                    sqrtP = FpSqrt(ORDER);
                return sqrtP(f, n);
            }),
        toBytes: (num) => (isLE ? (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.numberToBytesLE)(num, BYTES) : (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.numberToBytesBE)(num, BYTES)),
        fromBytes: (bytes) => {
            if (bytes.length !== BYTES)
                throw new Error('Field.fromBytes: expected ' + BYTES + ' bytes, got ' + bytes.length);
            return isLE ? (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bytesToNumberLE)(bytes) : (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bytesToNumberBE)(bytes);
        },
        // TODO: we don't need it here, move out to separate fn
        invertBatch: (lst) => FpInvertBatch(f, lst),
        // We can't move this out because Fp6, Fp12 implement it
        // and it's unclear what to return in there.
        cmov: (a, b, c) => (c ? b : a),
    });
    return Object.freeze(f);
}
function FpSqrtOdd(Fp, elm) {
    if (!Fp.isOdd)
        throw new Error("Field doesn't have isOdd");
    const root = Fp.sqrt(elm);
    return Fp.isOdd(root) ? root : Fp.neg(root);
}
function FpSqrtEven(Fp, elm) {
    if (!Fp.isOdd)
        throw new Error("Field doesn't have isOdd");
    const root = Fp.sqrt(elm);
    return Fp.isOdd(root) ? Fp.neg(root) : root;
}
/**
 * "Constant-time" private key generation utility.
 * Same as mapKeyToField, but accepts less bytes (40 instead of 48 for 32-byte field).
 * Which makes it slightly more biased, less secure.
 * @deprecated use `mapKeyToField` instead
 */
function hashToPrivateScalar(hash, groupOrder, isLE = false) {
    hash = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.ensureBytes)('privateHash', hash);
    const hashLen = hash.length;
    const minLen = nLength(groupOrder).nByteLength + 8;
    if (minLen < 24 || hashLen < minLen || hashLen > 1024)
        throw new Error('hashToPrivateScalar: expected ' + minLen + '-1024 bytes of input, got ' + hashLen);
    const num = isLE ? (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bytesToNumberLE)(hash) : (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bytesToNumberBE)(hash);
    return mod(num, groupOrder - _1n) + _1n;
}
/**
 * Returns total number of bytes consumed by the field element.
 * For example, 32 bytes for usual 256-bit weierstrass curve.
 * @param fieldOrder number of field elements, usually CURVE.n
 * @returns byte length of field
 */
function getFieldBytesLength(fieldOrder) {
    if (typeof fieldOrder !== 'bigint')
        throw new Error('field order must be bigint');
    const bitLength = fieldOrder.toString(2).length;
    return Math.ceil(bitLength / 8);
}
/**
 * Returns minimal amount of bytes that can be safely reduced
 * by field order.
 * Should be 2^-128 for 128-bit curve such as P256.
 * @param fieldOrder number of field elements, usually CURVE.n
 * @returns byte length of target hash
 */
function getMinHashLength(fieldOrder) {
    const length = getFieldBytesLength(fieldOrder);
    return length + Math.ceil(length / 2);
}
/**
 * "Constant-time" private key generation utility.
 * Can take (n + n/2) or more bytes of uniform input e.g. from CSPRNG or KDF
 * and convert them into private scalar, with the modulo bias being negligible.
 * Needs at least 48 bytes of input for 32-byte private key.
 * https://research.kudelskisecurity.com/2020/07/28/the-definitive-guide-to-modulo-bias-and-how-to-avoid-it/
 * FIPS 186-5, A.2 https://csrc.nist.gov/publications/detail/fips/186/5/final
 * RFC 9380, https://www.rfc-editor.org/rfc/rfc9380#section-5
 * @param hash hash output from SHA3 or a similar function
 * @param groupOrder size of subgroup - (e.g. secp256k1.CURVE.n)
 * @param isLE interpret hash bytes as LE num
 * @returns valid private scalar
 */
function mapHashToField(key, fieldOrder, isLE = false) {
    const len = key.length;
    const fieldLen = getFieldBytesLength(fieldOrder);
    const minLen = getMinHashLength(fieldOrder);
    // No small numbers: need to understand bias story. No huge numbers: easier to detect JS timings.
    if (len < 16 || len < minLen || len > 1024)
        throw new Error('expected ' + minLen + '-1024 bytes of input, got ' + len);
    const num = isLE ? (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bytesToNumberLE)(key) : (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bytesToNumberBE)(key);
    // `mod(x, 11)` can sometimes produce 0. `mod(x, 10) + 1` is the same, but no 0
    const reduced = mod(num, fieldOrder - _1n) + _1n;
    return isLE ? (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.numberToBytesLE)(reduced, fieldLen) : (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.numberToBytesBE)(reduced, fieldLen);
}
//# sourceMappingURL=modular.js.map

/***/ }),

/***/ "./node_modules/@noble/curves/esm/abstract/utils.js":
/*!**********************************************************!*\
  !*** ./node_modules/@noble/curves/esm/abstract/utils.js ***!
  \**********************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   aInRange: () => (/* binding */ aInRange),
/* harmony export */   abool: () => (/* binding */ abool),
/* harmony export */   abytes: () => (/* binding */ abytes),
/* harmony export */   bitGet: () => (/* binding */ bitGet),
/* harmony export */   bitLen: () => (/* binding */ bitLen),
/* harmony export */   bitMask: () => (/* binding */ bitMask),
/* harmony export */   bitSet: () => (/* binding */ bitSet),
/* harmony export */   bytesToHex: () => (/* binding */ bytesToHex),
/* harmony export */   bytesToNumberBE: () => (/* binding */ bytesToNumberBE),
/* harmony export */   bytesToNumberLE: () => (/* binding */ bytesToNumberLE),
/* harmony export */   concatBytes: () => (/* binding */ concatBytes),
/* harmony export */   createHmacDrbg: () => (/* binding */ createHmacDrbg),
/* harmony export */   ensureBytes: () => (/* binding */ ensureBytes),
/* harmony export */   equalBytes: () => (/* binding */ equalBytes),
/* harmony export */   hexToBytes: () => (/* binding */ hexToBytes),
/* harmony export */   hexToNumber: () => (/* binding */ hexToNumber),
/* harmony export */   inRange: () => (/* binding */ inRange),
/* harmony export */   isBytes: () => (/* binding */ isBytes),
/* harmony export */   memoized: () => (/* binding */ memoized),
/* harmony export */   notImplemented: () => (/* binding */ notImplemented),
/* harmony export */   numberToBytesBE: () => (/* binding */ numberToBytesBE),
/* harmony export */   numberToBytesLE: () => (/* binding */ numberToBytesLE),
/* harmony export */   numberToHexUnpadded: () => (/* binding */ numberToHexUnpadded),
/* harmony export */   numberToVarBytesBE: () => (/* binding */ numberToVarBytesBE),
/* harmony export */   utf8ToBytes: () => (/* binding */ utf8ToBytes),
/* harmony export */   validateObject: () => (/* binding */ validateObject)
/* harmony export */ });
/**
 * Hex, bytes and number utilities.
 * @module
 */
/*! noble-curves - MIT License (c) 2022 Paul Miller (paulmillr.com) */
// 100 lines of code in the file are duplicated from noble-hashes (utils).
// This is OK: `abstract` directory does not use noble-hashes.
// User may opt-in into using different hashing library. This way, noble-hashes
// won't be included into their bundle.
const _0n = /* @__PURE__ */ BigInt(0);
const _1n = /* @__PURE__ */ BigInt(1);
function isBytes(a) {
    return a instanceof Uint8Array || (ArrayBuffer.isView(a) && a.constructor.name === 'Uint8Array');
}
function abytes(item) {
    if (!isBytes(item))
        throw new Error('Uint8Array expected');
}
function abool(title, value) {
    if (typeof value !== 'boolean')
        throw new Error(title + ' boolean expected, got ' + value);
}
// Used in weierstrass, der
function numberToHexUnpadded(num) {
    const hex = num.toString(16);
    return hex.length & 1 ? '0' + hex : hex;
}
function hexToNumber(hex) {
    if (typeof hex !== 'string')
        throw new Error('hex string expected, got ' + typeof hex);
    return hex === '' ? _0n : BigInt('0x' + hex); // Big Endian
}
// Built-in hex conversion https://caniuse.com/mdn-javascript_builtins_uint8array_fromhex
const hasHexBuiltin = 
// @ts-ignore
typeof Uint8Array.from([]).toHex === 'function' && typeof Uint8Array.fromHex === 'function';
// Array where index 0xf0 (240) is mapped to string 'f0'
const hexes = /* @__PURE__ */ Array.from({ length: 256 }, (_, i) => i.toString(16).padStart(2, '0'));
/**
 * Convert byte array to hex string. Uses built-in function, when available.
 * @example bytesToHex(Uint8Array.from([0xca, 0xfe, 0x01, 0x23])) // 'cafe0123'
 */
function bytesToHex(bytes) {
    abytes(bytes);
    // @ts-ignore
    if (hasHexBuiltin)
        return bytes.toHex();
    // pre-caching improves the speed 6x
    let hex = '';
    for (let i = 0; i < bytes.length; i++) {
        hex += hexes[bytes[i]];
    }
    return hex;
}
// We use optimized technique to convert hex string to byte array
const asciis = { _0: 48, _9: 57, A: 65, F: 70, a: 97, f: 102 };
function asciiToBase16(ch) {
    if (ch >= asciis._0 && ch <= asciis._9)
        return ch - asciis._0; // '2' => 50-48
    if (ch >= asciis.A && ch <= asciis.F)
        return ch - (asciis.A - 10); // 'B' => 66-(65-10)
    if (ch >= asciis.a && ch <= asciis.f)
        return ch - (asciis.a - 10); // 'b' => 98-(97-10)
    return;
}
/**
 * Convert hex string to byte array. Uses built-in function, when available.
 * @example hexToBytes('cafe0123') // Uint8Array.from([0xca, 0xfe, 0x01, 0x23])
 */
function hexToBytes(hex) {
    if (typeof hex !== 'string')
        throw new Error('hex string expected, got ' + typeof hex);
    // @ts-ignore
    if (hasHexBuiltin)
        return Uint8Array.fromHex(hex);
    const hl = hex.length;
    const al = hl / 2;
    if (hl % 2)
        throw new Error('hex string expected, got unpadded hex of length ' + hl);
    const array = new Uint8Array(al);
    for (let ai = 0, hi = 0; ai < al; ai++, hi += 2) {
        const n1 = asciiToBase16(hex.charCodeAt(hi));
        const n2 = asciiToBase16(hex.charCodeAt(hi + 1));
        if (n1 === undefined || n2 === undefined) {
            const char = hex[hi] + hex[hi + 1];
            throw new Error('hex string expected, got non-hex character "' + char + '" at index ' + hi);
        }
        array[ai] = n1 * 16 + n2; // multiply first octet, e.g. 'a3' => 10*16+3 => 160 + 3 => 163
    }
    return array;
}
// BE: Big Endian, LE: Little Endian
function bytesToNumberBE(bytes) {
    return hexToNumber(bytesToHex(bytes));
}
function bytesToNumberLE(bytes) {
    abytes(bytes);
    return hexToNumber(bytesToHex(Uint8Array.from(bytes).reverse()));
}
function numberToBytesBE(n, len) {
    return hexToBytes(n.toString(16).padStart(len * 2, '0'));
}
function numberToBytesLE(n, len) {
    return numberToBytesBE(n, len).reverse();
}
// Unpadded, rarely used
function numberToVarBytesBE(n) {
    return hexToBytes(numberToHexUnpadded(n));
}
/**
 * Takes hex string or Uint8Array, converts to Uint8Array.
 * Validates output length.
 * Will throw error for other types.
 * @param title descriptive title for an error e.g. 'private key'
 * @param hex hex string or Uint8Array
 * @param expectedLength optional, will compare to result array's length
 * @returns
 */
function ensureBytes(title, hex, expectedLength) {
    let res;
    if (typeof hex === 'string') {
        try {
            res = hexToBytes(hex);
        }
        catch (e) {
            throw new Error(title + ' must be hex string or Uint8Array, cause: ' + e);
        }
    }
    else if (isBytes(hex)) {
        // Uint8Array.from() instead of hash.slice() because node.js Buffer
        // is instance of Uint8Array, and its slice() creates **mutable** copy
        res = Uint8Array.from(hex);
    }
    else {
        throw new Error(title + ' must be hex string or Uint8Array');
    }
    const len = res.length;
    if (typeof expectedLength === 'number' && len !== expectedLength)
        throw new Error(title + ' of length ' + expectedLength + ' expected, got ' + len);
    return res;
}
/**
 * Copies several Uint8Arrays into one.
 */
function concatBytes(...arrays) {
    let sum = 0;
    for (let i = 0; i < arrays.length; i++) {
        const a = arrays[i];
        abytes(a);
        sum += a.length;
    }
    const res = new Uint8Array(sum);
    for (let i = 0, pad = 0; i < arrays.length; i++) {
        const a = arrays[i];
        res.set(a, pad);
        pad += a.length;
    }
    return res;
}
// Compares 2 u8a-s in kinda constant time
function equalBytes(a, b) {
    if (a.length !== b.length)
        return false;
    let diff = 0;
    for (let i = 0; i < a.length; i++)
        diff |= a[i] ^ b[i];
    return diff === 0;
}
/**
 * @example utf8ToBytes('abc') // new Uint8Array([97, 98, 99])
 */
function utf8ToBytes(str) {
    if (typeof str !== 'string')
        throw new Error('string expected');
    return new Uint8Array(new TextEncoder().encode(str)); // https://bugzil.la/1681809
}
// Is positive bigint
const isPosBig = (n) => typeof n === 'bigint' && _0n <= n;
function inRange(n, min, max) {
    return isPosBig(n) && isPosBig(min) && isPosBig(max) && min <= n && n < max;
}
/**
 * Asserts min <= n < max. NOTE: It's < max and not <= max.
 * @example
 * aInRange('x', x, 1n, 256n); // would assume x is in (1n..255n)
 */
function aInRange(title, n, min, max) {
    // Why min <= n < max and not a (min < n < max) OR b (min <= n <= max)?
    // consider P=256n, min=0n, max=P
    // - a for min=0 would require -1:          `inRange('x', x, -1n, P)`
    // - b would commonly require subtraction:  `inRange('x', x, 0n, P - 1n)`
    // - our way is the cleanest:               `inRange('x', x, 0n, P)
    if (!inRange(n, min, max))
        throw new Error('expected valid ' + title + ': ' + min + ' <= n < ' + max + ', got ' + n);
}
// Bit operations
/**
 * Calculates amount of bits in a bigint.
 * Same as `n.toString(2).length`
 * TODO: merge with nLength in modular
 */
function bitLen(n) {
    let len;
    for (len = 0; n > _0n; n >>= _1n, len += 1)
        ;
    return len;
}
/**
 * Gets single bit at position.
 * NOTE: first bit position is 0 (same as arrays)
 * Same as `!!+Array.from(n.toString(2)).reverse()[pos]`
 */
function bitGet(n, pos) {
    return (n >> BigInt(pos)) & _1n;
}
/**
 * Sets single bit at position.
 */
function bitSet(n, pos, value) {
    return n | ((value ? _1n : _0n) << BigInt(pos));
}
/**
 * Calculate mask for N bits. Not using ** operator with bigints because of old engines.
 * Same as BigInt(`0b${Array(i).fill('1').join('')}`)
 */
const bitMask = (n) => (_1n << BigInt(n)) - _1n;
// DRBG
const u8n = (len) => new Uint8Array(len); // creates Uint8Array
const u8fr = (arr) => Uint8Array.from(arr); // another shortcut
/**
 * Minimal HMAC-DRBG from NIST 800-90 for RFC6979 sigs.
 * @returns function that will call DRBG until 2nd arg returns something meaningful
 * @example
 *   const drbg = createHmacDRBG<Key>(32, 32, hmac);
 *   drbg(seed, bytesToKey); // bytesToKey must return Key or undefined
 */
function createHmacDrbg(hashLen, qByteLen, hmacFn) {
    if (typeof hashLen !== 'number' || hashLen < 2)
        throw new Error('hashLen must be a number');
    if (typeof qByteLen !== 'number' || qByteLen < 2)
        throw new Error('qByteLen must be a number');
    if (typeof hmacFn !== 'function')
        throw new Error('hmacFn must be a function');
    // Step B, Step C: set hashLen to 8*ceil(hlen/8)
    let v = u8n(hashLen); // Minimal non-full-spec HMAC-DRBG from NIST 800-90 for RFC6979 sigs.
    let k = u8n(hashLen); // Steps B and C of RFC6979 3.2: set hashLen, in our case always same
    let i = 0; // Iterations counter, will throw when over 1000
    const reset = () => {
        v.fill(1);
        k.fill(0);
        i = 0;
    };
    const h = (...b) => hmacFn(k, v, ...b); // hmac(k)(v, ...values)
    const reseed = (seed = u8n(0)) => {
        // HMAC-DRBG reseed() function. Steps D-G
        k = h(u8fr([0x00]), seed); // k = hmac(k || v || 0x00 || seed)
        v = h(); // v = hmac(k || v)
        if (seed.length === 0)
            return;
        k = h(u8fr([0x01]), seed); // k = hmac(k || v || 0x01 || seed)
        v = h(); // v = hmac(k || v)
    };
    const gen = () => {
        // HMAC-DRBG generate() function
        if (i++ >= 1000)
            throw new Error('drbg: tried 1000 values');
        let len = 0;
        const out = [];
        while (len < qByteLen) {
            v = h();
            const sl = v.slice();
            out.push(sl);
            len += v.length;
        }
        return concatBytes(...out);
    };
    const genUntil = (seed, pred) => {
        reset();
        reseed(seed); // Steps D-G
        let res = undefined; // Step H: grind until k is in [1..n-1]
        while (!(res = pred(gen())))
            reseed();
        reset();
        return res;
    };
    return genUntil;
}
// Validating curves and fields
const validatorFns = {
    bigint: (val) => typeof val === 'bigint',
    function: (val) => typeof val === 'function',
    boolean: (val) => typeof val === 'boolean',
    string: (val) => typeof val === 'string',
    stringOrUint8Array: (val) => typeof val === 'string' || isBytes(val),
    isSafeInteger: (val) => Number.isSafeInteger(val),
    array: (val) => Array.isArray(val),
    field: (val, object) => object.Fp.isValid(val),
    hash: (val) => typeof val === 'function' && Number.isSafeInteger(val.outputLen),
};
// type Record<K extends string | number | symbol, T> = { [P in K]: T; }
function validateObject(object, validators, optValidators = {}) {
    const checkField = (fieldName, type, isOptional) => {
        const checkVal = validatorFns[type];
        if (typeof checkVal !== 'function')
            throw new Error('invalid validator function');
        const val = object[fieldName];
        if (isOptional && val === undefined)
            return;
        if (!checkVal(val, object)) {
            throw new Error('param ' + String(fieldName) + ' is invalid. Expected ' + type + ', got ' + val);
        }
    };
    for (const [fieldName, type] of Object.entries(validators))
        checkField(fieldName, type, false);
    for (const [fieldName, type] of Object.entries(optValidators))
        checkField(fieldName, type, true);
    return object;
}
// validate type tests
// const o: { a: number; b: number; c: number } = { a: 1, b: 5, c: 6 };
// const z0 = validateObject(o, { a: 'isSafeInteger' }, { c: 'bigint' }); // Ok!
// // Should fail type-check
// const z1 = validateObject(o, { a: 'tmp' }, { c: 'zz' });
// const z2 = validateObject(o, { a: 'isSafeInteger' }, { c: 'zz' });
// const z3 = validateObject(o, { test: 'boolean', z: 'bug' });
// const z4 = validateObject(o, { a: 'boolean', z: 'bug' });
/**
 * throws not implemented error
 */
const notImplemented = () => {
    throw new Error('not implemented');
};
/**
 * Memoizes (caches) computation result.
 * Uses WeakMap: the value is going auto-cleaned by GC after last reference is removed.
 */
function memoized(fn) {
    const map = new WeakMap();
    return (arg, ...args) => {
        const val = map.get(arg);
        if (val !== undefined)
            return val;
        const computed = fn(arg, ...args);
        map.set(arg, computed);
        return computed;
    };
}
//# sourceMappingURL=utils.js.map

/***/ }),

/***/ "./node_modules/@noble/curves/esm/abstract/weierstrass.js":
/*!****************************************************************!*\
  !*** ./node_modules/@noble/curves/esm/abstract/weierstrass.js ***!
  \****************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   DER: () => (/* binding */ DER),
/* harmony export */   DERErr: () => (/* binding */ DERErr),
/* harmony export */   SWUFpSqrtRatio: () => (/* binding */ SWUFpSqrtRatio),
/* harmony export */   mapToCurveSimpleSWU: () => (/* binding */ mapToCurveSimpleSWU),
/* harmony export */   weierstrass: () => (/* binding */ weierstrass),
/* harmony export */   weierstrassPoints: () => (/* binding */ weierstrassPoints)
/* harmony export */ });
/* harmony import */ var _curve_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ./curve.js */ "./node_modules/@noble/curves/esm/abstract/curve.js");
/* harmony import */ var _modular_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ./modular.js */ "./node_modules/@noble/curves/esm/abstract/modular.js");
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./utils.js */ "./node_modules/@noble/curves/esm/abstract/utils.js");
/**
 * Short Weierstrass curve methods. The formula is: y² = x³ + ax + b.
 *
 * ### Parameters
 *
 * To initialize a weierstrass curve, one needs to pass following params:
 *
 * * a: formula param
 * * b: formula param
 * * Fp: finite field of prime characteristic P; may be complex (Fp2). Arithmetics is done in field
 * * n: order of prime subgroup a.k.a total amount of valid curve points
 * * Gx: Base point (x, y) aka generator point. Gx = x coordinate
 * * Gy: ...y coordinate
 * * h: cofactor, usually 1. h*n = curve group order (n is only subgroup order)
 * * lowS: whether to enable (default) or disable "low-s" non-malleable signatures
 *
 * ### Design rationale for types
 *
 * * Interaction between classes from different curves should fail:
 *   `k256.Point.BASE.add(p256.Point.BASE)`
 * * For this purpose we want to use `instanceof` operator, which is fast and works during runtime
 * * Different calls of `curve()` would return different classes -
 *   `curve(params) !== curve(params)`: if somebody decided to monkey-patch their curve,
 *   it won't affect others
 *
 * TypeScript can't infer types for classes created inside a function. Classes is one instance
 * of nominative types in TypeScript and interfaces only check for shape, so it's hard to create
 * unique type for every function call.
 *
 * We can use generic types via some param, like curve opts, but that would:
 *     1. Enable interaction between `curve(params)` and `curve(params)` (curves of same params)
 *     which is hard to debug.
 *     2. Params can be generic and we can't enforce them to be constant value:
 *     if somebody creates curve from non-constant params,
 *     it would be allowed to interact with other curves with non-constant params
 *
 * @todo https://www.typescriptlang.org/docs/handbook/release-notes/typescript-2-7.html#unique-symbol
 * @module
 */
/*! noble-curves - MIT License (c) 2022 Paul Miller (paulmillr.com) */
// prettier-ignore

// prettier-ignore

// prettier-ignore

function validateSigVerOpts(opts) {
    if (opts.lowS !== undefined)
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abool)('lowS', opts.lowS);
    if (opts.prehash !== undefined)
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abool)('prehash', opts.prehash);
}
function validatePointOpts(curve) {
    const opts = (0,_curve_js__WEBPACK_IMPORTED_MODULE_1__.validateBasic)(curve);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.validateObject)(opts, {
        a: 'field',
        b: 'field',
    }, {
        allowInfinityPoint: 'boolean',
        allowedPrivateKeyLengths: 'array',
        clearCofactor: 'function',
        fromBytes: 'function',
        isTorsionFree: 'function',
        toBytes: 'function',
        wrapPrivateKey: 'boolean',
    });
    const { endo, Fp, a } = opts;
    if (endo) {
        if (!Fp.eql(a, Fp.ZERO)) {
            throw new Error('invalid endo: CURVE.a must be 0');
        }
        if (typeof endo !== 'object' ||
            typeof endo.beta !== 'bigint' ||
            typeof endo.splitScalar !== 'function') {
            throw new Error('invalid endo: expected "beta": bigint and "splitScalar": function');
        }
    }
    return Object.freeze({ ...opts });
}
class DERErr extends Error {
    constructor(m = '') {
        super(m);
    }
}
/**
 * ASN.1 DER encoding utilities. ASN is very complex & fragile. Format:
 *
 *     [0x30 (SEQUENCE), bytelength, 0x02 (INTEGER), intLength, R, 0x02 (INTEGER), intLength, S]
 *
 * Docs: https://letsencrypt.org/docs/a-warm-welcome-to-asn1-and-der/, https://luca.ntop.org/Teaching/Appunti/asn1.html
 */
const DER = {
    // asn.1 DER encoding utils
    Err: DERErr,
    // Basic building block is TLV (Tag-Length-Value)
    _tlv: {
        encode: (tag, data) => {
            const { Err: E } = DER;
            if (tag < 0 || tag > 256)
                throw new E('tlv.encode: wrong tag');
            if (data.length & 1)
                throw new E('tlv.encode: unpadded data');
            const dataLen = data.length / 2;
            const len = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.numberToHexUnpadded)(dataLen);
            if ((len.length / 2) & 128)
                throw new E('tlv.encode: long form length too big');
            // length of length with long form flag
            const lenLen = dataLen > 127 ? (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.numberToHexUnpadded)((len.length / 2) | 128) : '';
            const t = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.numberToHexUnpadded)(tag);
            return t + lenLen + len + data;
        },
        // v - value, l - left bytes (unparsed)
        decode(tag, data) {
            const { Err: E } = DER;
            let pos = 0;
            if (tag < 0 || tag > 256)
                throw new E('tlv.encode: wrong tag');
            if (data.length < 2 || data[pos++] !== tag)
                throw new E('tlv.decode: wrong tlv');
            const first = data[pos++];
            const isLong = !!(first & 128); // First bit of first length byte is flag for short/long form
            let length = 0;
            if (!isLong)
                length = first;
            else {
                // Long form: [longFlag(1bit), lengthLength(7bit), length (BE)]
                const lenLen = first & 127;
                if (!lenLen)
                    throw new E('tlv.decode(long): indefinite length not supported');
                if (lenLen > 4)
                    throw new E('tlv.decode(long): byte length is too big'); // this will overflow u32 in js
                const lengthBytes = data.subarray(pos, pos + lenLen);
                if (lengthBytes.length !== lenLen)
                    throw new E('tlv.decode: length bytes not complete');
                if (lengthBytes[0] === 0)
                    throw new E('tlv.decode(long): zero leftmost byte');
                for (const b of lengthBytes)
                    length = (length << 8) | b;
                pos += lenLen;
                if (length < 128)
                    throw new E('tlv.decode(long): not minimal encoding');
            }
            const v = data.subarray(pos, pos + length);
            if (v.length !== length)
                throw new E('tlv.decode: wrong value length');
            return { v, l: data.subarray(pos + length) };
        },
    },
    // https://crypto.stackexchange.com/a/57734 Leftmost bit of first byte is 'negative' flag,
    // since we always use positive integers here. It must always be empty:
    // - add zero byte if exists
    // - if next byte doesn't have a flag, leading zero is not allowed (minimal encoding)
    _int: {
        encode(num) {
            const { Err: E } = DER;
            if (num < _0n)
                throw new E('integer: negative integers are not allowed');
            let hex = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.numberToHexUnpadded)(num);
            // Pad with zero byte if negative flag is present
            if (Number.parseInt(hex[0], 16) & 0b1000)
                hex = '00' + hex;
            if (hex.length & 1)
                throw new E('unexpected DER parsing assertion: unpadded hex');
            return hex;
        },
        decode(data) {
            const { Err: E } = DER;
            if (data[0] & 128)
                throw new E('invalid signature integer: negative');
            if (data[0] === 0x00 && !(data[1] & 128))
                throw new E('invalid signature integer: unnecessary leading zero');
            return (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bytesToNumberBE)(data);
        },
    },
    toSig(hex) {
        // parse DER signature
        const { Err: E, _int: int, _tlv: tlv } = DER;
        const data = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.ensureBytes)('signature', hex);
        const { v: seqBytes, l: seqLeftBytes } = tlv.decode(0x30, data);
        if (seqLeftBytes.length)
            throw new E('invalid signature: left bytes after parsing');
        const { v: rBytes, l: rLeftBytes } = tlv.decode(0x02, seqBytes);
        const { v: sBytes, l: sLeftBytes } = tlv.decode(0x02, rLeftBytes);
        if (sLeftBytes.length)
            throw new E('invalid signature: left bytes after parsing');
        return { r: int.decode(rBytes), s: int.decode(sBytes) };
    },
    hexFromSig(sig) {
        const { _tlv: tlv, _int: int } = DER;
        const rs = tlv.encode(0x02, int.encode(sig.r));
        const ss = tlv.encode(0x02, int.encode(sig.s));
        const seq = rs + ss;
        return tlv.encode(0x30, seq);
    },
};
function numToSizedHex(num, size) {
    return (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bytesToHex)((0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.numberToBytesBE)(num, size));
}
// Be friendly to bad ECMAScript parsers by not using bigint literals
// prettier-ignore
const _0n = BigInt(0), _1n = BigInt(1), _2n = BigInt(2), _3n = BigInt(3), _4n = BigInt(4);
function weierstrassPoints(opts) {
    const CURVE = validatePointOpts(opts);
    const { Fp } = CURVE; // All curves has same field / group length as for now, but they can differ
    const Fn = (0,_modular_js__WEBPACK_IMPORTED_MODULE_2__.Field)(CURVE.n, CURVE.nBitLength);
    const toBytes = CURVE.toBytes ||
        ((_c, point, _isCompressed) => {
            const a = point.toAffine();
            return (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.concatBytes)(Uint8Array.from([0x04]), Fp.toBytes(a.x), Fp.toBytes(a.y));
        });
    const fromBytes = CURVE.fromBytes ||
        ((bytes) => {
            // const head = bytes[0];
            const tail = bytes.subarray(1);
            // if (head !== 0x04) throw new Error('Only non-compressed encoding is supported');
            const x = Fp.fromBytes(tail.subarray(0, Fp.BYTES));
            const y = Fp.fromBytes(tail.subarray(Fp.BYTES, 2 * Fp.BYTES));
            return { x, y };
        });
    /**
     * y² = x³ + ax + b: Short weierstrass curve formula. Takes x, returns y².
     * @returns y²
     */
    function weierstrassEquation(x) {
        const { a, b } = CURVE;
        const x2 = Fp.sqr(x); // x * x
        const x3 = Fp.mul(x2, x); // x² * x
        return Fp.add(Fp.add(x3, Fp.mul(x, a)), b); // x³ + a * x + b
    }
    function isValidXY(x, y) {
        const left = Fp.sqr(y); // y²
        const right = weierstrassEquation(x); // x³ + ax + b
        return Fp.eql(left, right);
    }
    // Validate whether the passed curve params are valid.
    // Test 1: equation y² = x³ + ax + b should work for generator point.
    if (!isValidXY(CURVE.Gx, CURVE.Gy))
        throw new Error('bad curve params: generator point');
    // Test 2: discriminant Δ part should be non-zero: 4a³ + 27b² != 0.
    // Guarantees curve is genus-1, smooth (non-singular).
    const _4a3 = Fp.mul(Fp.pow(CURVE.a, _3n), _4n);
    const _27b2 = Fp.mul(Fp.sqr(CURVE.b), BigInt(27));
    if (Fp.is0(Fp.add(_4a3, _27b2)))
        throw new Error('bad curve params: a or b');
    // Valid group elements reside in range 1..n-1
    function isWithinCurveOrder(num) {
        return (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.inRange)(num, _1n, CURVE.n);
    }
    // Validates if priv key is valid and converts it to bigint.
    // Supports options allowedPrivateKeyLengths and wrapPrivateKey.
    function normPrivateKeyToScalar(key) {
        const { allowedPrivateKeyLengths: lengths, nByteLength, wrapPrivateKey, n: N } = CURVE;
        if (lengths && typeof key !== 'bigint') {
            if ((0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.isBytes)(key))
                key = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bytesToHex)(key);
            // Normalize to hex string, pad. E.g. P521 would norm 130-132 char hex to 132-char bytes
            if (typeof key !== 'string' || !lengths.includes(key.length))
                throw new Error('invalid private key');
            key = key.padStart(nByteLength * 2, '0');
        }
        let num;
        try {
            num =
                typeof key === 'bigint'
                    ? key
                    : (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bytesToNumberBE)((0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.ensureBytes)('private key', key, nByteLength));
        }
        catch (error) {
            throw new Error('invalid private key, expected hex or ' + nByteLength + ' bytes, got ' + typeof key);
        }
        if (wrapPrivateKey)
            num = (0,_modular_js__WEBPACK_IMPORTED_MODULE_2__.mod)(num, N); // disabled by default, enabled for BLS
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aInRange)('private key', num, _1n, N); // num in range [1..N-1]
        return num;
    }
    function aprjpoint(other) {
        if (!(other instanceof Point))
            throw new Error('ProjectivePoint expected');
    }
    // Memoized toAffine / validity check. They are heavy. Points are immutable.
    // Converts Projective point to affine (x, y) coordinates.
    // Can accept precomputed Z^-1 - for example, from invertBatch.
    // (X, Y, Z) ∋ (x=X/Z, y=Y/Z)
    const toAffineMemo = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.memoized)((p, iz) => {
        const { px: x, py: y, pz: z } = p;
        // Fast-path for normalized points
        if (Fp.eql(z, Fp.ONE))
            return { x, y };
        const is0 = p.is0();
        // If invZ was 0, we return zero point. However we still want to execute
        // all operations, so we replace invZ with a random number, 1.
        if (iz == null)
            iz = is0 ? Fp.ONE : Fp.inv(z);
        const ax = Fp.mul(x, iz);
        const ay = Fp.mul(y, iz);
        const zz = Fp.mul(z, iz);
        if (is0)
            return { x: Fp.ZERO, y: Fp.ZERO };
        if (!Fp.eql(zz, Fp.ONE))
            throw new Error('invZ was invalid');
        return { x: ax, y: ay };
    });
    // NOTE: on exception this will crash 'cached' and no value will be set.
    // Otherwise true will be return
    const assertValidMemo = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.memoized)((p) => {
        if (p.is0()) {
            // (0, 1, 0) aka ZERO is invalid in most contexts.
            // In BLS, ZERO can be serialized, so we allow it.
            // (0, 0, 0) is invalid representation of ZERO.
            if (CURVE.allowInfinityPoint && !Fp.is0(p.py))
                return;
            throw new Error('bad point: ZERO');
        }
        // Some 3rd-party test vectors require different wording between here & `fromCompressedHex`
        const { x, y } = p.toAffine();
        // Check if x, y are valid field elements
        if (!Fp.isValid(x) || !Fp.isValid(y))
            throw new Error('bad point: x or y not FE');
        if (!isValidXY(x, y))
            throw new Error('bad point: equation left != right');
        if (!p.isTorsionFree())
            throw new Error('bad point: not in prime-order subgroup');
        return true;
    });
    /**
     * Projective Point works in 3d / projective (homogeneous) coordinates: (X, Y, Z) ∋ (x=X/Z, y=Y/Z)
     * Default Point works in 2d / affine coordinates: (x, y)
     * We're doing calculations in projective, because its operations don't require costly inversion.
     */
    class Point {
        constructor(px, py, pz) {
            if (px == null || !Fp.isValid(px))
                throw new Error('x required');
            if (py == null || !Fp.isValid(py) || Fp.is0(py))
                throw new Error('y required');
            if (pz == null || !Fp.isValid(pz))
                throw new Error('z required');
            this.px = px;
            this.py = py;
            this.pz = pz;
            Object.freeze(this);
        }
        // Does not validate if the point is on-curve.
        // Use fromHex instead, or call assertValidity() later.
        static fromAffine(p) {
            const { x, y } = p || {};
            if (!p || !Fp.isValid(x) || !Fp.isValid(y))
                throw new Error('invalid affine point');
            if (p instanceof Point)
                throw new Error('projective point not allowed');
            const is0 = (i) => Fp.eql(i, Fp.ZERO);
            // fromAffine(x:0, y:0) would produce (x:0, y:0, z:1), but we need (x:0, y:1, z:0)
            if (is0(x) && is0(y))
                return Point.ZERO;
            return new Point(x, y, Fp.ONE);
        }
        get x() {
            return this.toAffine().x;
        }
        get y() {
            return this.toAffine().y;
        }
        /**
         * Takes a bunch of Projective Points but executes only one
         * inversion on all of them. Inversion is very slow operation,
         * so this improves performance massively.
         * Optimization: converts a list of projective points to a list of identical points with Z=1.
         */
        static normalizeZ(points) {
            const toInv = (0,_modular_js__WEBPACK_IMPORTED_MODULE_2__.FpInvertBatch)(Fp, points.map((p) => p.pz));
            return points.map((p, i) => p.toAffine(toInv[i])).map(Point.fromAffine);
        }
        /**
         * Converts hash string or Uint8Array to Point.
         * @param hex short/long ECDSA hex
         */
        static fromHex(hex) {
            const P = Point.fromAffine(fromBytes((0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.ensureBytes)('pointHex', hex)));
            P.assertValidity();
            return P;
        }
        // Multiplies generator point by privateKey.
        static fromPrivateKey(privateKey) {
            return Point.BASE.multiply(normPrivateKeyToScalar(privateKey));
        }
        // Multiscalar Multiplication
        static msm(points, scalars) {
            return (0,_curve_js__WEBPACK_IMPORTED_MODULE_1__.pippenger)(Point, Fn, points, scalars);
        }
        // "Private method", don't use it directly
        _setWindowSize(windowSize) {
            wnaf.setWindowSize(this, windowSize);
        }
        // A point on curve is valid if it conforms to equation.
        assertValidity() {
            assertValidMemo(this);
        }
        hasEvenY() {
            const { y } = this.toAffine();
            if (Fp.isOdd)
                return !Fp.isOdd(y);
            throw new Error("Field doesn't support isOdd");
        }
        /**
         * Compare one point to another.
         */
        equals(other) {
            aprjpoint(other);
            const { px: X1, py: Y1, pz: Z1 } = this;
            const { px: X2, py: Y2, pz: Z2 } = other;
            const U1 = Fp.eql(Fp.mul(X1, Z2), Fp.mul(X2, Z1));
            const U2 = Fp.eql(Fp.mul(Y1, Z2), Fp.mul(Y2, Z1));
            return U1 && U2;
        }
        /**
         * Flips point to one corresponding to (x, -y) in Affine coordinates.
         */
        negate() {
            return new Point(this.px, Fp.neg(this.py), this.pz);
        }
        // Renes-Costello-Batina exception-free doubling formula.
        // There is 30% faster Jacobian formula, but it is not complete.
        // https://eprint.iacr.org/2015/1060, algorithm 3
        // Cost: 8M + 3S + 3*a + 2*b3 + 15add.
        double() {
            const { a, b } = CURVE;
            const b3 = Fp.mul(b, _3n);
            const { px: X1, py: Y1, pz: Z1 } = this;
            let X3 = Fp.ZERO, Y3 = Fp.ZERO, Z3 = Fp.ZERO; // prettier-ignore
            let t0 = Fp.mul(X1, X1); // step 1
            let t1 = Fp.mul(Y1, Y1);
            let t2 = Fp.mul(Z1, Z1);
            let t3 = Fp.mul(X1, Y1);
            t3 = Fp.add(t3, t3); // step 5
            Z3 = Fp.mul(X1, Z1);
            Z3 = Fp.add(Z3, Z3);
            X3 = Fp.mul(a, Z3);
            Y3 = Fp.mul(b3, t2);
            Y3 = Fp.add(X3, Y3); // step 10
            X3 = Fp.sub(t1, Y3);
            Y3 = Fp.add(t1, Y3);
            Y3 = Fp.mul(X3, Y3);
            X3 = Fp.mul(t3, X3);
            Z3 = Fp.mul(b3, Z3); // step 15
            t2 = Fp.mul(a, t2);
            t3 = Fp.sub(t0, t2);
            t3 = Fp.mul(a, t3);
            t3 = Fp.add(t3, Z3);
            Z3 = Fp.add(t0, t0); // step 20
            t0 = Fp.add(Z3, t0);
            t0 = Fp.add(t0, t2);
            t0 = Fp.mul(t0, t3);
            Y3 = Fp.add(Y3, t0);
            t2 = Fp.mul(Y1, Z1); // step 25
            t2 = Fp.add(t2, t2);
            t0 = Fp.mul(t2, t3);
            X3 = Fp.sub(X3, t0);
            Z3 = Fp.mul(t2, t1);
            Z3 = Fp.add(Z3, Z3); // step 30
            Z3 = Fp.add(Z3, Z3);
            return new Point(X3, Y3, Z3);
        }
        // Renes-Costello-Batina exception-free addition formula.
        // There is 30% faster Jacobian formula, but it is not complete.
        // https://eprint.iacr.org/2015/1060, algorithm 1
        // Cost: 12M + 0S + 3*a + 3*b3 + 23add.
        add(other) {
            aprjpoint(other);
            const { px: X1, py: Y1, pz: Z1 } = this;
            const { px: X2, py: Y2, pz: Z2 } = other;
            let X3 = Fp.ZERO, Y3 = Fp.ZERO, Z3 = Fp.ZERO; // prettier-ignore
            const a = CURVE.a;
            const b3 = Fp.mul(CURVE.b, _3n);
            let t0 = Fp.mul(X1, X2); // step 1
            let t1 = Fp.mul(Y1, Y2);
            let t2 = Fp.mul(Z1, Z2);
            let t3 = Fp.add(X1, Y1);
            let t4 = Fp.add(X2, Y2); // step 5
            t3 = Fp.mul(t3, t4);
            t4 = Fp.add(t0, t1);
            t3 = Fp.sub(t3, t4);
            t4 = Fp.add(X1, Z1);
            let t5 = Fp.add(X2, Z2); // step 10
            t4 = Fp.mul(t4, t5);
            t5 = Fp.add(t0, t2);
            t4 = Fp.sub(t4, t5);
            t5 = Fp.add(Y1, Z1);
            X3 = Fp.add(Y2, Z2); // step 15
            t5 = Fp.mul(t5, X3);
            X3 = Fp.add(t1, t2);
            t5 = Fp.sub(t5, X3);
            Z3 = Fp.mul(a, t4);
            X3 = Fp.mul(b3, t2); // step 20
            Z3 = Fp.add(X3, Z3);
            X3 = Fp.sub(t1, Z3);
            Z3 = Fp.add(t1, Z3);
            Y3 = Fp.mul(X3, Z3);
            t1 = Fp.add(t0, t0); // step 25
            t1 = Fp.add(t1, t0);
            t2 = Fp.mul(a, t2);
            t4 = Fp.mul(b3, t4);
            t1 = Fp.add(t1, t2);
            t2 = Fp.sub(t0, t2); // step 30
            t2 = Fp.mul(a, t2);
            t4 = Fp.add(t4, t2);
            t0 = Fp.mul(t1, t4);
            Y3 = Fp.add(Y3, t0);
            t0 = Fp.mul(t5, t4); // step 35
            X3 = Fp.mul(t3, X3);
            X3 = Fp.sub(X3, t0);
            t0 = Fp.mul(t3, t1);
            Z3 = Fp.mul(t5, Z3);
            Z3 = Fp.add(Z3, t0); // step 40
            return new Point(X3, Y3, Z3);
        }
        subtract(other) {
            return this.add(other.negate());
        }
        is0() {
            return this.equals(Point.ZERO);
        }
        wNAF(n) {
            return wnaf.wNAFCached(this, n, Point.normalizeZ);
        }
        /**
         * Non-constant-time multiplication. Uses double-and-add algorithm.
         * It's faster, but should only be used when you don't care about
         * an exposed private key e.g. sig verification, which works over *public* keys.
         */
        multiplyUnsafe(sc) {
            const { endo, n: N } = CURVE;
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aInRange)('scalar', sc, _0n, N);
            const I = Point.ZERO;
            if (sc === _0n)
                return I;
            if (this.is0() || sc === _1n)
                return this;
            // Case a: no endomorphism. Case b: has precomputes.
            if (!endo || wnaf.hasPrecomputes(this))
                return wnaf.wNAFCachedUnsafe(this, sc, Point.normalizeZ);
            // Case c: endomorphism
            /** See docs for {@link EndomorphismOpts} */
            let { k1neg, k1, k2neg, k2 } = endo.splitScalar(sc);
            let k1p = I;
            let k2p = I;
            let d = this;
            while (k1 > _0n || k2 > _0n) {
                if (k1 & _1n)
                    k1p = k1p.add(d);
                if (k2 & _1n)
                    k2p = k2p.add(d);
                d = d.double();
                k1 >>= _1n;
                k2 >>= _1n;
            }
            if (k1neg)
                k1p = k1p.negate();
            if (k2neg)
                k2p = k2p.negate();
            k2p = new Point(Fp.mul(k2p.px, endo.beta), k2p.py, k2p.pz);
            return k1p.add(k2p);
        }
        /**
         * Constant time multiplication.
         * Uses wNAF method. Windowed method may be 10% faster,
         * but takes 2x longer to generate and consumes 2x memory.
         * Uses precomputes when available.
         * Uses endomorphism for Koblitz curves.
         * @param scalar by which the point would be multiplied
         * @returns New point
         */
        multiply(scalar) {
            const { endo, n: N } = CURVE;
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aInRange)('scalar', scalar, _1n, N);
            let point, fake; // Fake point is used to const-time mult
            /** See docs for {@link EndomorphismOpts} */
            if (endo) {
                const { k1neg, k1, k2neg, k2 } = endo.splitScalar(scalar);
                let { p: k1p, f: f1p } = this.wNAF(k1);
                let { p: k2p, f: f2p } = this.wNAF(k2);
                k1p = wnaf.constTimeNegate(k1neg, k1p);
                k2p = wnaf.constTimeNegate(k2neg, k2p);
                k2p = new Point(Fp.mul(k2p.px, endo.beta), k2p.py, k2p.pz);
                point = k1p.add(k2p);
                fake = f1p.add(f2p);
            }
            else {
                const { p, f } = this.wNAF(scalar);
                point = p;
                fake = f;
            }
            // Normalize `z` for both points, but return only real one
            return Point.normalizeZ([point, fake])[0];
        }
        /**
         * Efficiently calculate `aP + bQ`. Unsafe, can expose private key, if used incorrectly.
         * Not using Strauss-Shamir trick: precomputation tables are faster.
         * The trick could be useful if both P and Q are not G (not in our case).
         * @returns non-zero affine point
         */
        multiplyAndAddUnsafe(Q, a, b) {
            const G = Point.BASE; // No Strauss-Shamir trick: we have 10% faster G precomputes
            const mul = (P, a // Select faster multiply() method
            ) => (a === _0n || a === _1n || !P.equals(G) ? P.multiplyUnsafe(a) : P.multiply(a));
            const sum = mul(this, a).add(mul(Q, b));
            return sum.is0() ? undefined : sum;
        }
        // Converts Projective point to affine (x, y) coordinates.
        // Can accept precomputed Z^-1 - for example, from invertBatch.
        // (x, y, z) ∋ (x=x/z, y=y/z)
        toAffine(iz) {
            return toAffineMemo(this, iz);
        }
        isTorsionFree() {
            const { h: cofactor, isTorsionFree } = CURVE;
            if (cofactor === _1n)
                return true; // No subgroups, always torsion-free
            if (isTorsionFree)
                return isTorsionFree(Point, this);
            throw new Error('isTorsionFree() has not been declared for the elliptic curve');
        }
        clearCofactor() {
            const { h: cofactor, clearCofactor } = CURVE;
            if (cofactor === _1n)
                return this; // Fast-path
            if (clearCofactor)
                return clearCofactor(Point, this);
            return this.multiplyUnsafe(CURVE.h);
        }
        toRawBytes(isCompressed = true) {
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abool)('isCompressed', isCompressed);
            this.assertValidity();
            return toBytes(Point, this, isCompressed);
        }
        toHex(isCompressed = true) {
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abool)('isCompressed', isCompressed);
            return (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bytesToHex)(this.toRawBytes(isCompressed));
        }
    }
    // base / generator point
    Point.BASE = new Point(CURVE.Gx, CURVE.Gy, Fp.ONE);
    // zero / infinity / identity point
    Point.ZERO = new Point(Fp.ZERO, Fp.ONE, Fp.ZERO); // 0, 1, 0
    const { endo, nBitLength } = CURVE;
    const wnaf = (0,_curve_js__WEBPACK_IMPORTED_MODULE_1__.wNAF)(Point, endo ? Math.ceil(nBitLength / 2) : nBitLength);
    return {
        CURVE,
        ProjectivePoint: Point,
        normPrivateKeyToScalar,
        weierstrassEquation,
        isWithinCurveOrder,
    };
}
function validateOpts(curve) {
    const opts = (0,_curve_js__WEBPACK_IMPORTED_MODULE_1__.validateBasic)(curve);
    (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.validateObject)(opts, {
        hash: 'hash',
        hmac: 'function',
        randomBytes: 'function',
    }, {
        bits2int: 'function',
        bits2int_modN: 'function',
        lowS: 'boolean',
    });
    return Object.freeze({ lowS: true, ...opts });
}
/**
 * Creates short weierstrass curve and ECDSA signature methods for it.
 * @example
 * import { Field } from '@noble/curves/abstract/modular';
 * // Before that, define BigInt-s: a, b, p, n, Gx, Gy
 * const curve = weierstrass({ a, b, Fp: Field(p), n, Gx, Gy, h: 1n })
 */
function weierstrass(curveDef) {
    const CURVE = validateOpts(curveDef);
    const { Fp, n: CURVE_ORDER, nByteLength, nBitLength } = CURVE;
    const compressedLen = Fp.BYTES + 1; // e.g. 33 for 32
    const uncompressedLen = 2 * Fp.BYTES + 1; // e.g. 65 for 32
    function modN(a) {
        return (0,_modular_js__WEBPACK_IMPORTED_MODULE_2__.mod)(a, CURVE_ORDER);
    }
    function invN(a) {
        return (0,_modular_js__WEBPACK_IMPORTED_MODULE_2__.invert)(a, CURVE_ORDER);
    }
    const { ProjectivePoint: Point, normPrivateKeyToScalar, weierstrassEquation, isWithinCurveOrder, } = weierstrassPoints({
        ...CURVE,
        toBytes(_c, point, isCompressed) {
            const a = point.toAffine();
            const x = Fp.toBytes(a.x);
            const cat = _utils_js__WEBPACK_IMPORTED_MODULE_0__.concatBytes;
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abool)('isCompressed', isCompressed);
            if (isCompressed) {
                return cat(Uint8Array.from([point.hasEvenY() ? 0x02 : 0x03]), x);
            }
            else {
                return cat(Uint8Array.from([0x04]), x, Fp.toBytes(a.y));
            }
        },
        fromBytes(bytes) {
            const len = bytes.length;
            const head = bytes[0];
            const tail = bytes.subarray(1);
            // this.assertValidity() is done inside of fromHex
            if (len === compressedLen && (head === 0x02 || head === 0x03)) {
                const x = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bytesToNumberBE)(tail);
                if (!(0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.inRange)(x, _1n, Fp.ORDER))
                    throw new Error('Point is not on curve');
                const y2 = weierstrassEquation(x); // y² = x³ + ax + b
                let y;
                try {
                    y = Fp.sqrt(y2); // y = y² ^ (p+1)/4
                }
                catch (sqrtError) {
                    const suffix = sqrtError instanceof Error ? ': ' + sqrtError.message : '';
                    throw new Error('Point is not on curve' + suffix);
                }
                const isYOdd = (y & _1n) === _1n;
                // ECDSA
                const isHeadOdd = (head & 1) === 1;
                if (isHeadOdd !== isYOdd)
                    y = Fp.neg(y);
                return { x, y };
            }
            else if (len === uncompressedLen && head === 0x04) {
                const x = Fp.fromBytes(tail.subarray(0, Fp.BYTES));
                const y = Fp.fromBytes(tail.subarray(Fp.BYTES, 2 * Fp.BYTES));
                return { x, y };
            }
            else {
                const cl = compressedLen;
                const ul = uncompressedLen;
                throw new Error('invalid Point, expected length of ' + cl + ', or uncompressed ' + ul + ', got ' + len);
            }
        },
    });
    function isBiggerThanHalfOrder(number) {
        const HALF = CURVE_ORDER >> _1n;
        return number > HALF;
    }
    function normalizeS(s) {
        return isBiggerThanHalfOrder(s) ? modN(-s) : s;
    }
    // slice bytes num
    const slcNum = (b, from, to) => (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bytesToNumberBE)(b.slice(from, to));
    /**
     * ECDSA signature with its (r, s) properties. Supports DER & compact representations.
     */
    class Signature {
        constructor(r, s, recovery) {
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aInRange)('r', r, _1n, CURVE_ORDER); // r in [1..N]
            (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aInRange)('s', s, _1n, CURVE_ORDER); // s in [1..N]
            this.r = r;
            this.s = s;
            if (recovery != null)
                this.recovery = recovery;
            Object.freeze(this);
        }
        // pair (bytes of r, bytes of s)
        static fromCompact(hex) {
            const l = nByteLength;
            hex = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.ensureBytes)('compactSignature', hex, l * 2);
            return new Signature(slcNum(hex, 0, l), slcNum(hex, l, 2 * l));
        }
        // DER encoded ECDSA signature
        // https://bitcoin.stackexchange.com/questions/57644/what-are-the-parts-of-a-bitcoin-transaction-input-script
        static fromDER(hex) {
            const { r, s } = DER.toSig((0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.ensureBytes)('DER', hex));
            return new Signature(r, s);
        }
        /**
         * @todo remove
         * @deprecated
         */
        assertValidity() { }
        addRecoveryBit(recovery) {
            return new Signature(this.r, this.s, recovery);
        }
        recoverPublicKey(msgHash) {
            const { r, s, recovery: rec } = this;
            const h = bits2int_modN((0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.ensureBytes)('msgHash', msgHash)); // Truncate hash
            if (rec == null || ![0, 1, 2, 3].includes(rec))
                throw new Error('recovery id invalid');
            const radj = rec === 2 || rec === 3 ? r + CURVE.n : r;
            if (radj >= Fp.ORDER)
                throw new Error('recovery id 2 or 3 invalid');
            const prefix = (rec & 1) === 0 ? '02' : '03';
            const R = Point.fromHex(prefix + numToSizedHex(radj, Fp.BYTES));
            const ir = invN(radj); // r^-1
            const u1 = modN(-h * ir); // -hr^-1
            const u2 = modN(s * ir); // sr^-1
            const Q = Point.BASE.multiplyAndAddUnsafe(R, u1, u2); // (sr^-1)R-(hr^-1)G = -(hr^-1)G + (sr^-1)
            if (!Q)
                throw new Error('point at infinify'); // unsafe is fine: no priv data leaked
            Q.assertValidity();
            return Q;
        }
        // Signatures should be low-s, to prevent malleability.
        hasHighS() {
            return isBiggerThanHalfOrder(this.s);
        }
        normalizeS() {
            return this.hasHighS() ? new Signature(this.r, modN(-this.s), this.recovery) : this;
        }
        // DER-encoded
        toDERRawBytes() {
            return (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.hexToBytes)(this.toDERHex());
        }
        toDERHex() {
            return DER.hexFromSig(this);
        }
        // padded bytes of r, then padded bytes of s
        toCompactRawBytes() {
            return (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.hexToBytes)(this.toCompactHex());
        }
        toCompactHex() {
            const l = nByteLength;
            return numToSizedHex(this.r, l) + numToSizedHex(this.s, l);
        }
    }
    const utils = {
        isValidPrivateKey(privateKey) {
            try {
                normPrivateKeyToScalar(privateKey);
                return true;
            }
            catch (error) {
                return false;
            }
        },
        normPrivateKeyToScalar: normPrivateKeyToScalar,
        /**
         * Produces cryptographically secure private key from random of size
         * (groupLen + ceil(groupLen / 2)) with modulo bias being negligible.
         */
        randomPrivateKey: () => {
            const length = (0,_modular_js__WEBPACK_IMPORTED_MODULE_2__.getMinHashLength)(CURVE.n);
            return (0,_modular_js__WEBPACK_IMPORTED_MODULE_2__.mapHashToField)(CURVE.randomBytes(length), CURVE.n);
        },
        /**
         * Creates precompute table for an arbitrary EC point. Makes point "cached".
         * Allows to massively speed-up `point.multiply(scalar)`.
         * @returns cached point
         * @example
         * const fast = utils.precompute(8, ProjectivePoint.fromHex(someonesPubKey));
         * fast.multiply(privKey); // much faster ECDH now
         */
        precompute(windowSize = 8, point = Point.BASE) {
            point._setWindowSize(windowSize);
            point.multiply(BigInt(3)); // 3 is arbitrary, just need any number here
            return point;
        },
    };
    /**
     * Computes public key for a private key. Checks for validity of the private key.
     * @param privateKey private key
     * @param isCompressed whether to return compact (default), or full key
     * @returns Public key, full when isCompressed=false; short when isCompressed=true
     */
    function getPublicKey(privateKey, isCompressed = true) {
        return Point.fromPrivateKey(privateKey).toRawBytes(isCompressed);
    }
    /**
     * Quick and dirty check for item being public key. Does not validate hex, or being on-curve.
     */
    function isProbPub(item) {
        if (typeof item === 'bigint')
            return false;
        if (item instanceof Point)
            return true;
        const arr = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.ensureBytes)('key', item);
        const len = arr.length;
        const fpl = Fp.BYTES;
        const compLen = fpl + 1; // e.g. 33 for 32
        const uncompLen = 2 * fpl + 1; // e.g. 65 for 32
        if (CURVE.allowedPrivateKeyLengths || nByteLength === compLen) {
            return undefined;
        }
        else {
            return len === compLen || len === uncompLen;
        }
    }
    /**
     * ECDH (Elliptic Curve Diffie Hellman).
     * Computes shared public key from private key and public key.
     * Checks: 1) private key validity 2) shared key is on-curve.
     * Does NOT hash the result.
     * @param privateA private key
     * @param publicB different public key
     * @param isCompressed whether to return compact (default), or full key
     * @returns shared public key
     */
    function getSharedSecret(privateA, publicB, isCompressed = true) {
        if (isProbPub(privateA) === true)
            throw new Error('first arg must be private key');
        if (isProbPub(publicB) === false)
            throw new Error('second arg must be public key');
        const b = Point.fromHex(publicB); // check for being on-curve
        return b.multiply(normPrivateKeyToScalar(privateA)).toRawBytes(isCompressed);
    }
    // RFC6979: ensure ECDSA msg is X bytes and < N. RFC suggests optional truncating via bits2octets.
    // FIPS 186-4 4.6 suggests the leftmost min(nBitLen, outLen) bits, which matches bits2int.
    // bits2int can produce res>N, we can do mod(res, N) since the bitLen is the same.
    // int2octets can't be used; pads small msgs with 0: unacceptatble for trunc as per RFC vectors
    const bits2int = CURVE.bits2int ||
        function (bytes) {
            // Our custom check "just in case", for protection against DoS
            if (bytes.length > 8192)
                throw new Error('input is too large');
            // For curves with nBitLength % 8 !== 0: bits2octets(bits2octets(m)) !== bits2octets(m)
            // for some cases, since bytes.length * 8 is not actual bitLength.
            const num = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bytesToNumberBE)(bytes); // check for == u8 done here
            const delta = bytes.length * 8 - nBitLength; // truncate to nBitLength leftmost bits
            return delta > 0 ? num >> BigInt(delta) : num;
        };
    const bits2int_modN = CURVE.bits2int_modN ||
        function (bytes) {
            return modN(bits2int(bytes)); // can't use bytesToNumberBE here
        };
    // NOTE: pads output with zero as per spec
    const ORDER_MASK = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.bitMask)(nBitLength);
    /**
     * Converts to bytes. Checks if num in `[0..ORDER_MASK-1]` e.g.: `[0..2^256-1]`.
     */
    function int2octets(num) {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aInRange)('num < 2^' + nBitLength, num, _0n, ORDER_MASK);
        // works with order, can have different size than numToField!
        return (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.numberToBytesBE)(num, nByteLength);
    }
    // Steps A, D of RFC6979 3.2
    // Creates RFC6979 seed; converts msg/privKey to numbers.
    // Used only in sign, not in verify.
    // NOTE: we cannot assume here that msgHash has same amount of bytes as curve order,
    // this will be invalid at least for P521. Also it can be bigger for P224 + SHA256
    function prepSig(msgHash, privateKey, opts = defaultSigOpts) {
        if (['recovered', 'canonical'].some((k) => k in opts))
            throw new Error('sign() legacy options not supported');
        const { hash, randomBytes } = CURVE;
        let { lowS, prehash, extraEntropy: ent } = opts; // generates low-s sigs by default
        if (lowS == null)
            lowS = true; // RFC6979 3.2: we skip step A, because we already provide hash
        msgHash = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.ensureBytes)('msgHash', msgHash);
        validateSigVerOpts(opts);
        if (prehash)
            msgHash = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.ensureBytes)('prehashed msgHash', hash(msgHash));
        // We can't later call bits2octets, since nested bits2int is broken for curves
        // with nBitLength % 8 !== 0. Because of that, we unwrap it here as int2octets call.
        // const bits2octets = (bits) => int2octets(bits2int_modN(bits))
        const h1int = bits2int_modN(msgHash);
        const d = normPrivateKeyToScalar(privateKey); // validate private key, convert to bigint
        const seedArgs = [int2octets(d), int2octets(h1int)];
        // extraEntropy. RFC6979 3.6: additional k' (optional).
        if (ent != null && ent !== false) {
            // K = HMAC_K(V || 0x00 || int2octets(x) || bits2octets(h1) || k')
            const e = ent === true ? randomBytes(Fp.BYTES) : ent; // generate random bytes OR pass as-is
            seedArgs.push((0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.ensureBytes)('extraEntropy', e)); // check for being bytes
        }
        const seed = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.concatBytes)(...seedArgs); // Step D of RFC6979 3.2
        const m = h1int; // NOTE: no need to call bits2int second time here, it is inside truncateHash!
        // Converts signature params into point w r/s, checks result for validity.
        function k2sig(kBytes) {
            // RFC 6979 Section 3.2, step 3: k = bits2int(T)
            const k = bits2int(kBytes); // Cannot use fields methods, since it is group element
            if (!isWithinCurveOrder(k))
                return; // Important: all mod() calls here must be done over N
            const ik = invN(k); // k^-1 mod n
            const q = Point.BASE.multiply(k).toAffine(); // q = Gk
            const r = modN(q.x); // r = q.x mod n
            if (r === _0n)
                return;
            // Can use scalar blinding b^-1(bm + bdr) where b ∈ [1,q−1] according to
            // https://tches.iacr.org/index.php/TCHES/article/view/7337/6509. We've decided against it:
            // a) dependency on CSPRNG b) 15% slowdown c) doesn't really help since bigints are not CT
            const s = modN(ik * modN(m + r * d)); // Not using blinding here
            if (s === _0n)
                return;
            let recovery = (q.x === r ? 0 : 2) | Number(q.y & _1n); // recovery bit (2 or 3, when q.x > n)
            let normS = s;
            if (lowS && isBiggerThanHalfOrder(s)) {
                normS = normalizeS(s); // if lowS was passed, ensure s is always
                recovery ^= 1; // // in the bottom half of N
            }
            return new Signature(r, normS, recovery); // use normS, not s
        }
        return { seed, k2sig };
    }
    const defaultSigOpts = { lowS: CURVE.lowS, prehash: false };
    const defaultVerOpts = { lowS: CURVE.lowS, prehash: false };
    /**
     * Signs message hash with a private key.
     * ```
     * sign(m, d, k) where
     *   (x, y) = G × k
     *   r = x mod n
     *   s = (m + dr)/k mod n
     * ```
     * @param msgHash NOT message. msg needs to be hashed to `msgHash`, or use `prehash`.
     * @param privKey private key
     * @param opts lowS for non-malleable sigs. extraEntropy for mixing randomness into k. prehash will hash first arg.
     * @returns signature with recovery param
     */
    function sign(msgHash, privKey, opts = defaultSigOpts) {
        const { seed, k2sig } = prepSig(msgHash, privKey, opts); // Steps A, D of RFC6979 3.2.
        const C = CURVE;
        const drbg = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.createHmacDrbg)(C.hash.outputLen, C.nByteLength, C.hmac);
        return drbg(seed, k2sig); // Steps B, C, D, E, F, G
    }
    // Enable precomputes. Slows down first publicKey computation by 20ms.
    Point.BASE._setWindowSize(8);
    // utils.precompute(8, ProjectivePoint.BASE)
    /**
     * Verifies a signature against message hash and public key.
     * Rejects lowS signatures by default: to override,
     * specify option `{lowS: false}`. Implements section 4.1.4 from https://www.secg.org/sec1-v2.pdf:
     *
     * ```
     * verify(r, s, h, P) where
     *   U1 = hs^-1 mod n
     *   U2 = rs^-1 mod n
     *   R = U1⋅G - U2⋅P
     *   mod(R.x, n) == r
     * ```
     */
    function verify(signature, msgHash, publicKey, opts = defaultVerOpts) {
        const sg = signature;
        msgHash = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.ensureBytes)('msgHash', msgHash);
        publicKey = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.ensureBytes)('publicKey', publicKey);
        const { lowS, prehash, format } = opts;
        // Verify opts, deduce signature format
        validateSigVerOpts(opts);
        if ('strict' in opts)
            throw new Error('options.strict was renamed to lowS');
        if (format !== undefined && format !== 'compact' && format !== 'der')
            throw new Error('format must be compact or der');
        const isHex = typeof sg === 'string' || (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.isBytes)(sg);
        const isObj = !isHex &&
            !format &&
            typeof sg === 'object' &&
            sg !== null &&
            typeof sg.r === 'bigint' &&
            typeof sg.s === 'bigint';
        if (!isHex && !isObj)
            throw new Error('invalid signature, expected Uint8Array, hex string or Signature instance');
        let _sig = undefined;
        let P;
        try {
            if (isObj)
                _sig = new Signature(sg.r, sg.s);
            if (isHex) {
                // Signature can be represented in 2 ways: compact (2*nByteLength) & DER (variable-length).
                // Since DER can also be 2*nByteLength bytes, we check for it first.
                try {
                    if (format !== 'compact')
                        _sig = Signature.fromDER(sg);
                }
                catch (derError) {
                    if (!(derError instanceof DER.Err))
                        throw derError;
                }
                if (!_sig && format !== 'der')
                    _sig = Signature.fromCompact(sg);
            }
            P = Point.fromHex(publicKey);
        }
        catch (error) {
            return false;
        }
        if (!_sig)
            return false;
        if (lowS && _sig.hasHighS())
            return false;
        if (prehash)
            msgHash = CURVE.hash(msgHash);
        const { r, s } = _sig;
        const h = bits2int_modN(msgHash); // Cannot use fields methods, since it is group element
        const is = invN(s); // s^-1
        const u1 = modN(h * is); // u1 = hs^-1 mod n
        const u2 = modN(r * is); // u2 = rs^-1 mod n
        const R = Point.BASE.multiplyAndAddUnsafe(P, u1, u2)?.toAffine(); // R = u1⋅G + u2⋅P
        if (!R)
            return false;
        const v = modN(R.x);
        return v === r;
    }
    return {
        CURVE,
        getPublicKey,
        getSharedSecret,
        sign,
        verify,
        ProjectivePoint: Point,
        Signature,
        utils,
    };
}
/**
 * Implementation of the Shallue and van de Woestijne method for any weierstrass curve.
 * TODO: check if there is a way to merge this with uvRatio in Edwards; move to modular.
 * b = True and y = sqrt(u / v) if (u / v) is square in F, and
 * b = False and y = sqrt(Z * (u / v)) otherwise.
 * @param Fp
 * @param Z
 * @returns
 */
function SWUFpSqrtRatio(Fp, Z) {
    // Generic implementation
    const q = Fp.ORDER;
    let l = _0n;
    for (let o = q - _1n; o % _2n === _0n; o /= _2n)
        l += _1n;
    const c1 = l; // 1. c1, the largest integer such that 2^c1 divides q - 1.
    // We need 2n ** c1 and 2n ** (c1-1). We can't use **; but we can use <<.
    // 2n ** c1 == 2n << (c1-1)
    const _2n_pow_c1_1 = _2n << (c1 - _1n - _1n);
    const _2n_pow_c1 = _2n_pow_c1_1 * _2n;
    const c2 = (q - _1n) / _2n_pow_c1; // 2. c2 = (q - 1) / (2^c1)  # Integer arithmetic
    const c3 = (c2 - _1n) / _2n; // 3. c3 = (c2 - 1) / 2            # Integer arithmetic
    const c4 = _2n_pow_c1 - _1n; // 4. c4 = 2^c1 - 1                # Integer arithmetic
    const c5 = _2n_pow_c1_1; // 5. c5 = 2^(c1 - 1)                  # Integer arithmetic
    const c6 = Fp.pow(Z, c2); // 6. c6 = Z^c2
    const c7 = Fp.pow(Z, (c2 + _1n) / _2n); // 7. c7 = Z^((c2 + 1) / 2)
    let sqrtRatio = (u, v) => {
        let tv1 = c6; // 1. tv1 = c6
        let tv2 = Fp.pow(v, c4); // 2. tv2 = v^c4
        let tv3 = Fp.sqr(tv2); // 3. tv3 = tv2^2
        tv3 = Fp.mul(tv3, v); // 4. tv3 = tv3 * v
        let tv5 = Fp.mul(u, tv3); // 5. tv5 = u * tv3
        tv5 = Fp.pow(tv5, c3); // 6. tv5 = tv5^c3
        tv5 = Fp.mul(tv5, tv2); // 7. tv5 = tv5 * tv2
        tv2 = Fp.mul(tv5, v); // 8. tv2 = tv5 * v
        tv3 = Fp.mul(tv5, u); // 9. tv3 = tv5 * u
        let tv4 = Fp.mul(tv3, tv2); // 10. tv4 = tv3 * tv2
        tv5 = Fp.pow(tv4, c5); // 11. tv5 = tv4^c5
        let isQR = Fp.eql(tv5, Fp.ONE); // 12. isQR = tv5 == 1
        tv2 = Fp.mul(tv3, c7); // 13. tv2 = tv3 * c7
        tv5 = Fp.mul(tv4, tv1); // 14. tv5 = tv4 * tv1
        tv3 = Fp.cmov(tv2, tv3, isQR); // 15. tv3 = CMOV(tv2, tv3, isQR)
        tv4 = Fp.cmov(tv5, tv4, isQR); // 16. tv4 = CMOV(tv5, tv4, isQR)
        // 17. for i in (c1, c1 - 1, ..., 2):
        for (let i = c1; i > _1n; i--) {
            let tv5 = i - _2n; // 18.    tv5 = i - 2
            tv5 = _2n << (tv5 - _1n); // 19.    tv5 = 2^tv5
            let tvv5 = Fp.pow(tv4, tv5); // 20.    tv5 = tv4^tv5
            const e1 = Fp.eql(tvv5, Fp.ONE); // 21.    e1 = tv5 == 1
            tv2 = Fp.mul(tv3, tv1); // 22.    tv2 = tv3 * tv1
            tv1 = Fp.mul(tv1, tv1); // 23.    tv1 = tv1 * tv1
            tvv5 = Fp.mul(tv4, tv1); // 24.    tv5 = tv4 * tv1
            tv3 = Fp.cmov(tv2, tv3, e1); // 25.    tv3 = CMOV(tv2, tv3, e1)
            tv4 = Fp.cmov(tvv5, tv4, e1); // 26.    tv4 = CMOV(tv5, tv4, e1)
        }
        return { isValid: isQR, value: tv3 };
    };
    if (Fp.ORDER % _4n === _3n) {
        // sqrt_ratio_3mod4(u, v)
        const c1 = (Fp.ORDER - _3n) / _4n; // 1. c1 = (q - 3) / 4     # Integer arithmetic
        const c2 = Fp.sqrt(Fp.neg(Z)); // 2. c2 = sqrt(-Z)
        sqrtRatio = (u, v) => {
            let tv1 = Fp.sqr(v); // 1. tv1 = v^2
            const tv2 = Fp.mul(u, v); // 2. tv2 = u * v
            tv1 = Fp.mul(tv1, tv2); // 3. tv1 = tv1 * tv2
            let y1 = Fp.pow(tv1, c1); // 4. y1 = tv1^c1
            y1 = Fp.mul(y1, tv2); // 5. y1 = y1 * tv2
            const y2 = Fp.mul(y1, c2); // 6. y2 = y1 * c2
            const tv3 = Fp.mul(Fp.sqr(y1), v); // 7. tv3 = y1^2; 8. tv3 = tv3 * v
            const isQR = Fp.eql(tv3, u); // 9. isQR = tv3 == u
            let y = Fp.cmov(y2, y1, isQR); // 10. y = CMOV(y2, y1, isQR)
            return { isValid: isQR, value: y }; // 11. return (isQR, y) isQR ? y : y*c2
        };
    }
    // No curves uses that
    // if (Fp.ORDER % _8n === _5n) // sqrt_ratio_5mod8
    return sqrtRatio;
}
/**
 * Simplified Shallue-van de Woestijne-Ulas Method
 * https://www.rfc-editor.org/rfc/rfc9380#section-6.6.2
 */
function mapToCurveSimpleSWU(Fp, opts) {
    (0,_modular_js__WEBPACK_IMPORTED_MODULE_2__.validateField)(Fp);
    if (!Fp.isValid(opts.A) || !Fp.isValid(opts.B) || !Fp.isValid(opts.Z))
        throw new Error('mapToCurveSimpleSWU: invalid opts');
    const sqrtRatio = SWUFpSqrtRatio(Fp, opts.Z);
    if (!Fp.isOdd)
        throw new Error('Fp.isOdd is not implemented!');
    // Input: u, an element of F.
    // Output: (x, y), a point on E.
    return (u) => {
        // prettier-ignore
        let tv1, tv2, tv3, tv4, tv5, tv6, x, y;
        tv1 = Fp.sqr(u); // 1.  tv1 = u^2
        tv1 = Fp.mul(tv1, opts.Z); // 2.  tv1 = Z * tv1
        tv2 = Fp.sqr(tv1); // 3.  tv2 = tv1^2
        tv2 = Fp.add(tv2, tv1); // 4.  tv2 = tv2 + tv1
        tv3 = Fp.add(tv2, Fp.ONE); // 5.  tv3 = tv2 + 1
        tv3 = Fp.mul(tv3, opts.B); // 6.  tv3 = B * tv3
        tv4 = Fp.cmov(opts.Z, Fp.neg(tv2), !Fp.eql(tv2, Fp.ZERO)); // 7.  tv4 = CMOV(Z, -tv2, tv2 != 0)
        tv4 = Fp.mul(tv4, opts.A); // 8.  tv4 = A * tv4
        tv2 = Fp.sqr(tv3); // 9.  tv2 = tv3^2
        tv6 = Fp.sqr(tv4); // 10. tv6 = tv4^2
        tv5 = Fp.mul(tv6, opts.A); // 11. tv5 = A * tv6
        tv2 = Fp.add(tv2, tv5); // 12. tv2 = tv2 + tv5
        tv2 = Fp.mul(tv2, tv3); // 13. tv2 = tv2 * tv3
        tv6 = Fp.mul(tv6, tv4); // 14. tv6 = tv6 * tv4
        tv5 = Fp.mul(tv6, opts.B); // 15. tv5 = B * tv6
        tv2 = Fp.add(tv2, tv5); // 16. tv2 = tv2 + tv5
        x = Fp.mul(tv1, tv3); // 17.   x = tv1 * tv3
        const { isValid, value } = sqrtRatio(tv2, tv6); // 18. (is_gx1_square, y1) = sqrt_ratio(tv2, tv6)
        y = Fp.mul(tv1, u); // 19.   y = tv1 * u  -> Z * u^3 * y1
        y = Fp.mul(y, value); // 20.   y = y * y1
        x = Fp.cmov(x, tv3, isValid); // 21.   x = CMOV(x, tv3, is_gx1_square)
        y = Fp.cmov(y, value, isValid); // 22.   y = CMOV(y, y1, is_gx1_square)
        const e1 = Fp.isOdd(u) === Fp.isOdd(y); // 23.  e1 = sgn0(u) == sgn0(y)
        y = Fp.cmov(Fp.neg(y), y, e1); // 24.   y = CMOV(-y, y, e1)
        const tv4_inv = (0,_modular_js__WEBPACK_IMPORTED_MODULE_2__.FpInvertBatch)(Fp, [tv4], true)[0];
        x = Fp.mul(x, tv4_inv); // 25.   x = x / tv4
        return { x, y };
    };
}
//# sourceMappingURL=weierstrass.js.map

/***/ }),

/***/ "./node_modules/@noble/curves/esm/secp256k1.js":
/*!*****************************************************!*\
  !*** ./node_modules/@noble/curves/esm/secp256k1.js ***!
  \*****************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   encodeToCurve: () => (/* binding */ encodeToCurve),
/* harmony export */   hashToCurve: () => (/* binding */ hashToCurve),
/* harmony export */   schnorr: () => (/* binding */ schnorr),
/* harmony export */   secp256k1: () => (/* binding */ secp256k1),
/* harmony export */   secp256k1_hasher: () => (/* binding */ secp256k1_hasher)
/* harmony export */ });
/* harmony import */ var _noble_hashes_sha2__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! @noble/hashes/sha2 */ "./node_modules/@noble/hashes/esm/sha2.js");
/* harmony import */ var _noble_hashes_utils__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! @noble/hashes/utils */ "./node_modules/@noble/hashes/esm/utils.js");
/* harmony import */ var _shortw_utils_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ./_shortw_utils.js */ "./node_modules/@noble/curves/esm/_shortw_utils.js");
/* harmony import */ var _abstract_hash_to_curve_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! ./abstract/hash-to-curve.js */ "./node_modules/@noble/curves/esm/abstract/hash-to-curve.js");
/* harmony import */ var _abstract_modular_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./abstract/modular.js */ "./node_modules/@noble/curves/esm/abstract/modular.js");
/* harmony import */ var _abstract_utils_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! ./abstract/utils.js */ "./node_modules/@noble/curves/esm/abstract/utils.js");
/* harmony import */ var _abstract_weierstrass_js__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(/*! ./abstract/weierstrass.js */ "./node_modules/@noble/curves/esm/abstract/weierstrass.js");
/**
 * NIST secp256k1. See [pdf](https://www.secg.org/sec2-v2.pdf).
 *
 * Seems to be rigid (not backdoored)
 * [as per discussion](https://bitcointalk.org/index.php?topic=289795.msg3183975#msg3183975).
 *
 * secp256k1 belongs to Koblitz curves: it has efficiently computable endomorphism.
 * Endomorphism uses 2x less RAM, speeds up precomputation by 2x and ECDH / key recovery by 20%.
 * For precomputed wNAF it trades off 1/2 init time & 1/3 ram for 20% perf hit.
 * [See explanation](https://gist.github.com/paulmillr/eb670806793e84df628a7c434a873066).
 * @module
 */
/*! noble-curves - MIT License (c) 2022 Paul Miller (paulmillr.com) */







const secp256k1P = BigInt('0xfffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f');
const secp256k1N = BigInt('0xfffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141');
const _0n = BigInt(0);
const _1n = BigInt(1);
const _2n = BigInt(2);
const divNearest = (a, b) => (a + b / _2n) / b;
/**
 * √n = n^((p+1)/4) for fields p = 3 mod 4. We unwrap the loop and multiply bit-by-bit.
 * (P+1n/4n).toString(2) would produce bits [223x 1, 0, 22x 1, 4x 0, 11, 00]
 */
function sqrtMod(y) {
    const P = secp256k1P;
    // prettier-ignore
    const _3n = BigInt(3), _6n = BigInt(6), _11n = BigInt(11), _22n = BigInt(22);
    // prettier-ignore
    const _23n = BigInt(23), _44n = BigInt(44), _88n = BigInt(88);
    const b2 = (y * y * y) % P; // x^3, 11
    const b3 = (b2 * b2 * y) % P; // x^7
    const b6 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_0__.pow2)(b3, _3n, P) * b3) % P;
    const b9 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_0__.pow2)(b6, _3n, P) * b3) % P;
    const b11 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_0__.pow2)(b9, _2n, P) * b2) % P;
    const b22 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_0__.pow2)(b11, _11n, P) * b11) % P;
    const b44 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_0__.pow2)(b22, _22n, P) * b22) % P;
    const b88 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_0__.pow2)(b44, _44n, P) * b44) % P;
    const b176 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_0__.pow2)(b88, _88n, P) * b88) % P;
    const b220 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_0__.pow2)(b176, _44n, P) * b44) % P;
    const b223 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_0__.pow2)(b220, _3n, P) * b3) % P;
    const t1 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_0__.pow2)(b223, _23n, P) * b22) % P;
    const t2 = ((0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_0__.pow2)(t1, _6n, P) * b2) % P;
    const root = (0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_0__.pow2)(t2, _2n, P);
    if (!Fpk1.eql(Fpk1.sqr(root), y))
        throw new Error('Cannot find square root');
    return root;
}
const Fpk1 = (0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_0__.Field)(secp256k1P, undefined, undefined, { sqrt: sqrtMod });
/**
 * secp256k1 curve, ECDSA and ECDH methods.
 *
 * Field: `2n**256n - 2n**32n - 2n**9n - 2n**8n - 2n**7n - 2n**6n - 2n**4n - 1n`
 *
 * @example
 * ```js
 * import { secp256k1 } from '@noble/curves/secp256k1';
 * const priv = secp256k1.utils.randomPrivateKey();
 * const pub = secp256k1.getPublicKey(priv);
 * const msg = new Uint8Array(32).fill(1); // message hash (not message) in ecdsa
 * const sig = secp256k1.sign(msg, priv); // `{prehash: true}` option is available
 * const isValid = secp256k1.verify(sig, msg, pub) === true;
 * ```
 */
const secp256k1 = (0,_shortw_utils_js__WEBPACK_IMPORTED_MODULE_1__.createCurve)({
    a: _0n,
    b: BigInt(7),
    Fp: Fpk1,
    n: secp256k1N,
    Gx: BigInt('55066263022277343669578718895168534326250603453777594175500187360389116729240'),
    Gy: BigInt('32670510020758816978083085130507043184471273380659243275938904335757337482424'),
    h: BigInt(1),
    lowS: true, // Allow only low-S signatures by default in sign() and verify()
    endo: {
        // Endomorphism, see above
        beta: BigInt('0x7ae96a2b657c07106e64479eac3434e99cf0497512f58995c1396c28719501ee'),
        splitScalar: (k) => {
            const n = secp256k1N;
            const a1 = BigInt('0x3086d221a7d46bcde86c90e49284eb15');
            const b1 = -_1n * BigInt('0xe4437ed6010e88286f547fa90abfe4c3');
            const a2 = BigInt('0x114ca50f7a8e2f3f657c1108d9d44cfd8');
            const b2 = a1;
            const POW_2_128 = BigInt('0x100000000000000000000000000000000'); // (2n**128n).toString(16)
            const c1 = divNearest(b2 * k, n);
            const c2 = divNearest(-b1 * k, n);
            let k1 = (0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_0__.mod)(k - c1 * a1 - c2 * a2, n);
            let k2 = (0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_0__.mod)(-c1 * b1 - c2 * b2, n);
            const k1neg = k1 > POW_2_128;
            const k2neg = k2 > POW_2_128;
            if (k1neg)
                k1 = n - k1;
            if (k2neg)
                k2 = n - k2;
            if (k1 > POW_2_128 || k2 > POW_2_128) {
                throw new Error('splitScalar: Endomorphism failed, k=' + k);
            }
            return { k1neg, k1, k2neg, k2 };
        },
    },
}, _noble_hashes_sha2__WEBPACK_IMPORTED_MODULE_2__.sha256);
// Schnorr signatures are superior to ECDSA from above. Below is Schnorr-specific BIP0340 code.
// https://github.com/bitcoin/bips/blob/master/bip-0340.mediawiki
/** An object mapping tags to their tagged hash prefix of [SHA256(tag) | SHA256(tag)] */
const TAGGED_HASH_PREFIXES = {};
function taggedHash(tag, ...messages) {
    let tagP = TAGGED_HASH_PREFIXES[tag];
    if (tagP === undefined) {
        const tagH = (0,_noble_hashes_sha2__WEBPACK_IMPORTED_MODULE_2__.sha256)(Uint8Array.from(tag, (c) => c.charCodeAt(0)));
        tagP = (0,_abstract_utils_js__WEBPACK_IMPORTED_MODULE_3__.concatBytes)(tagH, tagH);
        TAGGED_HASH_PREFIXES[tag] = tagP;
    }
    return (0,_noble_hashes_sha2__WEBPACK_IMPORTED_MODULE_2__.sha256)((0,_abstract_utils_js__WEBPACK_IMPORTED_MODULE_3__.concatBytes)(tagP, ...messages));
}
// ECDSA compact points are 33-byte. Schnorr is 32: we strip first byte 0x02 or 0x03
const pointToBytes = (point) => point.toRawBytes(true).slice(1);
const numTo32b = (n) => (0,_abstract_utils_js__WEBPACK_IMPORTED_MODULE_3__.numberToBytesBE)(n, 32);
const modP = (x) => (0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_0__.mod)(x, secp256k1P);
const modN = (x) => (0,_abstract_modular_js__WEBPACK_IMPORTED_MODULE_0__.mod)(x, secp256k1N);
const Point = /* @__PURE__ */ (() => secp256k1.ProjectivePoint)();
const GmulAdd = (Q, a, b) => Point.BASE.multiplyAndAddUnsafe(Q, a, b);
// Calculate point, scalar and bytes
function schnorrGetExtPubKey(priv) {
    let d_ = secp256k1.utils.normPrivateKeyToScalar(priv); // same method executed in fromPrivateKey
    let p = Point.fromPrivateKey(d_); // P = d'⋅G; 0 < d' < n check is done inside
    const scalar = p.hasEvenY() ? d_ : modN(-d_);
    return { scalar: scalar, bytes: pointToBytes(p) };
}
/**
 * lift_x from BIP340. Convert 32-byte x coordinate to elliptic curve point.
 * @returns valid point checked for being on-curve
 */
function lift_x(x) {
    (0,_abstract_utils_js__WEBPACK_IMPORTED_MODULE_3__.aInRange)('x', x, _1n, secp256k1P); // Fail if x ≥ p.
    const xx = modP(x * x);
    const c = modP(xx * x + BigInt(7)); // Let c = x³ + 7 mod p.
    let y = sqrtMod(c); // Let y = c^(p+1)/4 mod p.
    if (y % _2n !== _0n)
        y = modP(-y); // Return the unique point P such that x(P) = x and
    const p = new Point(x, y, _1n); // y(P) = y if y mod 2 = 0 or y(P) = p-y otherwise.
    p.assertValidity();
    return p;
}
const num = _abstract_utils_js__WEBPACK_IMPORTED_MODULE_3__.bytesToNumberBE;
/**
 * Create tagged hash, convert it to bigint, reduce modulo-n.
 */
function challenge(...args) {
    return modN(num(taggedHash('BIP0340/challenge', ...args)));
}
/**
 * Schnorr public key is just `x` coordinate of Point as per BIP340.
 */
function schnorrGetPublicKey(privateKey) {
    return schnorrGetExtPubKey(privateKey).bytes; // d'=int(sk). Fail if d'=0 or d'≥n. Ret bytes(d'⋅G)
}
/**
 * Creates Schnorr signature as per BIP340. Verifies itself before returning anything.
 * auxRand is optional and is not the sole source of k generation: bad CSPRNG won't be dangerous.
 */
function schnorrSign(message, privateKey, auxRand = (0,_noble_hashes_utils__WEBPACK_IMPORTED_MODULE_4__.randomBytes)(32)) {
    const m = (0,_abstract_utils_js__WEBPACK_IMPORTED_MODULE_3__.ensureBytes)('message', message);
    const { bytes: px, scalar: d } = schnorrGetExtPubKey(privateKey); // checks for isWithinCurveOrder
    const a = (0,_abstract_utils_js__WEBPACK_IMPORTED_MODULE_3__.ensureBytes)('auxRand', auxRand, 32); // Auxiliary random data a: a 32-byte array
    const t = numTo32b(d ^ num(taggedHash('BIP0340/aux', a))); // Let t be the byte-wise xor of bytes(d) and hash/aux(a)
    const rand = taggedHash('BIP0340/nonce', t, px, m); // Let rand = hash/nonce(t || bytes(P) || m)
    const k_ = modN(num(rand)); // Let k' = int(rand) mod n
    if (k_ === _0n)
        throw new Error('sign failed: k is zero'); // Fail if k' = 0.
    const { bytes: rx, scalar: k } = schnorrGetExtPubKey(k_); // Let R = k'⋅G.
    const e = challenge(rx, px, m); // Let e = int(hash/challenge(bytes(R) || bytes(P) || m)) mod n.
    const sig = new Uint8Array(64); // Let sig = bytes(R) || bytes((k + ed) mod n).
    sig.set(rx, 0);
    sig.set(numTo32b(modN(k + e * d)), 32);
    // If Verify(bytes(P), m, sig) (see below) returns failure, abort
    if (!schnorrVerify(sig, m, px))
        throw new Error('sign: Invalid signature produced');
    return sig;
}
/**
 * Verifies Schnorr signature.
 * Will swallow errors & return false except for initial type validation of arguments.
 */
function schnorrVerify(signature, message, publicKey) {
    const sig = (0,_abstract_utils_js__WEBPACK_IMPORTED_MODULE_3__.ensureBytes)('signature', signature, 64);
    const m = (0,_abstract_utils_js__WEBPACK_IMPORTED_MODULE_3__.ensureBytes)('message', message);
    const pub = (0,_abstract_utils_js__WEBPACK_IMPORTED_MODULE_3__.ensureBytes)('publicKey', publicKey, 32);
    try {
        const P = lift_x(num(pub)); // P = lift_x(int(pk)); fail if that fails
        const r = num(sig.subarray(0, 32)); // Let r = int(sig[0:32]); fail if r ≥ p.
        if (!(0,_abstract_utils_js__WEBPACK_IMPORTED_MODULE_3__.inRange)(r, _1n, secp256k1P))
            return false;
        const s = num(sig.subarray(32, 64)); // Let s = int(sig[32:64]); fail if s ≥ n.
        if (!(0,_abstract_utils_js__WEBPACK_IMPORTED_MODULE_3__.inRange)(s, _1n, secp256k1N))
            return false;
        const e = challenge(numTo32b(r), pointToBytes(P), m); // int(challenge(bytes(r)||bytes(P)||m))%n
        const R = GmulAdd(P, s, modN(-e)); // R = s⋅G - e⋅P
        if (!R || !R.hasEvenY() || R.toAffine().x !== r)
            return false; // -eP == (n-e)P
        return true; // Fail if is_infinite(R) / not has_even_y(R) / x(R) ≠ r.
    }
    catch (error) {
        return false;
    }
}
/**
 * Schnorr signatures over secp256k1.
 * https://github.com/bitcoin/bips/blob/master/bip-0340.mediawiki
 * @example
 * ```js
 * import { schnorr } from '@noble/curves/secp256k1';
 * const priv = schnorr.utils.randomPrivateKey();
 * const pub = schnorr.getPublicKey(priv);
 * const msg = new TextEncoder().encode('hello');
 * const sig = schnorr.sign(msg, priv);
 * const isValid = schnorr.verify(sig, msg, pub);
 * ```
 */
const schnorr = /* @__PURE__ */ (() => ({
    getPublicKey: schnorrGetPublicKey,
    sign: schnorrSign,
    verify: schnorrVerify,
    utils: {
        randomPrivateKey: secp256k1.utils.randomPrivateKey,
        lift_x,
        pointToBytes,
        numberToBytesBE: _abstract_utils_js__WEBPACK_IMPORTED_MODULE_3__.numberToBytesBE,
        bytesToNumberBE: _abstract_utils_js__WEBPACK_IMPORTED_MODULE_3__.bytesToNumberBE,
        taggedHash,
        mod: _abstract_modular_js__WEBPACK_IMPORTED_MODULE_0__.mod,
    },
}))();
const isoMap = /* @__PURE__ */ (() => (0,_abstract_hash_to_curve_js__WEBPACK_IMPORTED_MODULE_5__.isogenyMap)(Fpk1, [
    // xNum
    [
        '0x8e38e38e38e38e38e38e38e38e38e38e38e38e38e38e38e38e38e38daaaaa8c7',
        '0x7d3d4c80bc321d5b9f315cea7fd44c5d595d2fc0bf63b92dfff1044f17c6581',
        '0x534c328d23f234e6e2a413deca25caece4506144037c40314ecbd0b53d9dd262',
        '0x8e38e38e38e38e38e38e38e38e38e38e38e38e38e38e38e38e38e38daaaaa88c',
    ],
    // xDen
    [
        '0xd35771193d94918a9ca34ccbb7b640dd86cd409542f8487d9fe6b745781eb49b',
        '0xedadc6f64383dc1df7c4b2d51b54225406d36b641f5e41bbc52a56612a8c6d14',
        '0x0000000000000000000000000000000000000000000000000000000000000001', // LAST 1
    ],
    // yNum
    [
        '0x4bda12f684bda12f684bda12f684bda12f684bda12f684bda12f684b8e38e23c',
        '0xc75e0c32d5cb7c0fa9d0a54b12a0a6d5647ab046d686da6fdffc90fc201d71a3',
        '0x29a6194691f91a73715209ef6512e576722830a201be2018a765e85a9ecee931',
        '0x2f684bda12f684bda12f684bda12f684bda12f684bda12f684bda12f38e38d84',
    ],
    // yDen
    [
        '0xfffffffffffffffffffffffffffffffffffffffffffffffffffffffefffff93b',
        '0x7a06534bb8bdb49fd5e9e6632722c2989467c1bfc8e8d978dfb425d2685c2573',
        '0x6484aa716545ca2cf3a70c3fa8fe337e0a3d21162f0d6299a7bf8192bfd2a76f',
        '0x0000000000000000000000000000000000000000000000000000000000000001', // LAST 1
    ],
].map((i) => i.map((j) => BigInt(j)))))();
const mapSWU = /* @__PURE__ */ (() => (0,_abstract_weierstrass_js__WEBPACK_IMPORTED_MODULE_6__.mapToCurveSimpleSWU)(Fpk1, {
    A: BigInt('0x3f8731abdd661adca08a5558f0f5d272e953d363cb6f0e5d405447c01a444533'),
    B: BigInt('1771'),
    Z: Fpk1.create(BigInt('-11')),
}))();
/** Hashing / encoding to secp256k1 points / field. RFC 9380 methods. */
const secp256k1_hasher = /* @__PURE__ */ (() => (0,_abstract_hash_to_curve_js__WEBPACK_IMPORTED_MODULE_5__.createHasher)(secp256k1.ProjectivePoint, (scalars) => {
    const { x, y } = mapSWU(Fpk1.create(scalars[0]));
    return isoMap(x, y);
}, {
    DST: 'secp256k1_XMD:SHA-256_SSWU_RO_',
    encodeDST: 'secp256k1_XMD:SHA-256_SSWU_NU_',
    p: Fpk1.ORDER,
    m: 1,
    k: 128,
    expand: 'xmd',
    hash: _noble_hashes_sha2__WEBPACK_IMPORTED_MODULE_2__.sha256,
}))();
const hashToCurve = /* @__PURE__ */ (() => secp256k1_hasher.hashToCurve)();
const encodeToCurve = /* @__PURE__ */ (() => secp256k1_hasher.encodeToCurve)();
//# sourceMappingURL=secp256k1.js.map

/***/ }),

/***/ "./node_modules/@noble/hashes/esm/_md.js":
/*!***********************************************!*\
  !*** ./node_modules/@noble/hashes/esm/_md.js ***!
  \***********************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   Chi: () => (/* binding */ Chi),
/* harmony export */   HashMD: () => (/* binding */ HashMD),
/* harmony export */   Maj: () => (/* binding */ Maj),
/* harmony export */   SHA224_IV: () => (/* binding */ SHA224_IV),
/* harmony export */   SHA256_IV: () => (/* binding */ SHA256_IV),
/* harmony export */   SHA384_IV: () => (/* binding */ SHA384_IV),
/* harmony export */   SHA512_IV: () => (/* binding */ SHA512_IV),
/* harmony export */   setBigUint64: () => (/* binding */ setBigUint64)
/* harmony export */ });
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./utils.js */ "./node_modules/@noble/hashes/esm/utils.js");
/**
 * Internal Merkle-Damgard hash utils.
 * @module
 */

/** Polyfill for Safari 14. https://caniuse.com/mdn-javascript_builtins_dataview_setbiguint64 */
function setBigUint64(view, byteOffset, value, isLE) {
    if (typeof view.setBigUint64 === 'function')
        return view.setBigUint64(byteOffset, value, isLE);
    const _32n = BigInt(32);
    const _u32_max = BigInt(0xffffffff);
    const wh = Number((value >> _32n) & _u32_max);
    const wl = Number(value & _u32_max);
    const h = isLE ? 4 : 0;
    const l = isLE ? 0 : 4;
    view.setUint32(byteOffset + h, wh, isLE);
    view.setUint32(byteOffset + l, wl, isLE);
}
/** Choice: a ? b : c */
function Chi(a, b, c) {
    return (a & b) ^ (~a & c);
}
/** Majority function, true if any two inputs is true. */
function Maj(a, b, c) {
    return (a & b) ^ (a & c) ^ (b & c);
}
/**
 * Merkle-Damgard hash construction base class.
 * Could be used to create MD5, RIPEMD, SHA1, SHA2.
 */
class HashMD extends _utils_js__WEBPACK_IMPORTED_MODULE_0__.Hash {
    constructor(blockLen, outputLen, padOffset, isLE) {
        super();
        this.finished = false;
        this.length = 0;
        this.pos = 0;
        this.destroyed = false;
        this.blockLen = blockLen;
        this.outputLen = outputLen;
        this.padOffset = padOffset;
        this.isLE = isLE;
        this.buffer = new Uint8Array(blockLen);
        this.view = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.createView)(this.buffer);
    }
    update(data) {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aexists)(this);
        data = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.toBytes)(data);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abytes)(data);
        const { view, buffer, blockLen } = this;
        const len = data.length;
        for (let pos = 0; pos < len;) {
            const take = Math.min(blockLen - this.pos, len - pos);
            // Fast path: we have at least one block in input, cast it to view and process
            if (take === blockLen) {
                const dataView = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.createView)(data);
                for (; blockLen <= len - pos; pos += blockLen)
                    this.process(dataView, pos);
                continue;
            }
            buffer.set(data.subarray(pos, pos + take), this.pos);
            this.pos += take;
            pos += take;
            if (this.pos === blockLen) {
                this.process(view, 0);
                this.pos = 0;
            }
        }
        this.length += data.length;
        this.roundClean();
        return this;
    }
    digestInto(out) {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aexists)(this);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aoutput)(out, this);
        this.finished = true;
        // Padding
        // We can avoid allocation of buffer for padding completely if it
        // was previously not allocated here. But it won't change performance.
        const { buffer, view, blockLen, isLE } = this;
        let { pos } = this;
        // append the bit '1' to the message
        buffer[pos++] = 0b10000000;
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.clean)(this.buffer.subarray(pos));
        // we have less than padOffset left in buffer, so we cannot put length in
        // current block, need process it and pad again
        if (this.padOffset > blockLen - pos) {
            this.process(view, 0);
            pos = 0;
        }
        // Pad until full block byte with zeros
        for (let i = pos; i < blockLen; i++)
            buffer[i] = 0;
        // Note: sha512 requires length to be 128bit integer, but length in JS will overflow before that
        // You need to write around 2 exabytes (u64_max / 8 / (1024**6)) for this to happen.
        // So we just write lowest 64 bits of that value.
        setBigUint64(view, blockLen - 8, BigInt(this.length * 8), isLE);
        this.process(view, 0);
        const oview = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.createView)(out);
        const len = this.outputLen;
        // NOTE: we do division by 4 later, which should be fused in single op with modulo by JIT
        if (len % 4)
            throw new Error('_sha2: outputLen should be aligned to 32bit');
        const outLen = len / 4;
        const state = this.get();
        if (outLen > state.length)
            throw new Error('_sha2: outputLen bigger than state');
        for (let i = 0; i < outLen; i++)
            oview.setUint32(4 * i, state[i], isLE);
    }
    digest() {
        const { buffer, outputLen } = this;
        this.digestInto(buffer);
        const res = buffer.slice(0, outputLen);
        this.destroy();
        return res;
    }
    _cloneInto(to) {
        to || (to = new this.constructor());
        to.set(...this.get());
        const { blockLen, buffer, length, finished, destroyed, pos } = this;
        to.destroyed = destroyed;
        to.finished = finished;
        to.length = length;
        to.pos = pos;
        if (length % blockLen)
            to.buffer.set(buffer);
        return to;
    }
    clone() {
        return this._cloneInto();
    }
}
/**
 * Initial SHA-2 state: fractional parts of square roots of first 16 primes 2..53.
 * Check out `test/misc/sha2-gen-iv.js` for recomputation guide.
 */
/** Initial SHA256 state. Bits 0..32 of frac part of sqrt of primes 2..19 */
const SHA256_IV = /* @__PURE__ */ Uint32Array.from([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
]);
/** Initial SHA224 state. Bits 32..64 of frac part of sqrt of primes 23..53 */
const SHA224_IV = /* @__PURE__ */ Uint32Array.from([
    0xc1059ed8, 0x367cd507, 0x3070dd17, 0xf70e5939, 0xffc00b31, 0x68581511, 0x64f98fa7, 0xbefa4fa4,
]);
/** Initial SHA384 state. Bits 0..64 of frac part of sqrt of primes 23..53 */
const SHA384_IV = /* @__PURE__ */ Uint32Array.from([
    0xcbbb9d5d, 0xc1059ed8, 0x629a292a, 0x367cd507, 0x9159015a, 0x3070dd17, 0x152fecd8, 0xf70e5939,
    0x67332667, 0xffc00b31, 0x8eb44a87, 0x68581511, 0xdb0c2e0d, 0x64f98fa7, 0x47b5481d, 0xbefa4fa4,
]);
/** Initial SHA512 state. Bits 0..64 of frac part of sqrt of primes 2..19 */
const SHA512_IV = /* @__PURE__ */ Uint32Array.from([
    0x6a09e667, 0xf3bcc908, 0xbb67ae85, 0x84caa73b, 0x3c6ef372, 0xfe94f82b, 0xa54ff53a, 0x5f1d36f1,
    0x510e527f, 0xade682d1, 0x9b05688c, 0x2b3e6c1f, 0x1f83d9ab, 0xfb41bd6b, 0x5be0cd19, 0x137e2179,
]);
//# sourceMappingURL=_md.js.map

/***/ }),

/***/ "./node_modules/@noble/hashes/esm/_u64.js":
/*!************************************************!*\
  !*** ./node_modules/@noble/hashes/esm/_u64.js ***!
  \************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   add: () => (/* binding */ add),
/* harmony export */   add3H: () => (/* binding */ add3H),
/* harmony export */   add3L: () => (/* binding */ add3L),
/* harmony export */   add4H: () => (/* binding */ add4H),
/* harmony export */   add4L: () => (/* binding */ add4L),
/* harmony export */   add5H: () => (/* binding */ add5H),
/* harmony export */   add5L: () => (/* binding */ add5L),
/* harmony export */   "default": () => (__WEBPACK_DEFAULT_EXPORT__),
/* harmony export */   fromBig: () => (/* binding */ fromBig),
/* harmony export */   rotlBH: () => (/* binding */ rotlBH),
/* harmony export */   rotlBL: () => (/* binding */ rotlBL),
/* harmony export */   rotlSH: () => (/* binding */ rotlSH),
/* harmony export */   rotlSL: () => (/* binding */ rotlSL),
/* harmony export */   rotr32H: () => (/* binding */ rotr32H),
/* harmony export */   rotr32L: () => (/* binding */ rotr32L),
/* harmony export */   rotrBH: () => (/* binding */ rotrBH),
/* harmony export */   rotrBL: () => (/* binding */ rotrBL),
/* harmony export */   rotrSH: () => (/* binding */ rotrSH),
/* harmony export */   rotrSL: () => (/* binding */ rotrSL),
/* harmony export */   shrSH: () => (/* binding */ shrSH),
/* harmony export */   shrSL: () => (/* binding */ shrSL),
/* harmony export */   split: () => (/* binding */ split),
/* harmony export */   toBig: () => (/* binding */ toBig)
/* harmony export */ });
/**
 * Internal helpers for u64. BigUint64Array is too slow as per 2025, so we implement it using Uint32Array.
 * @todo re-check https://issues.chromium.org/issues/42212588
 * @module
 */
const U32_MASK64 = /* @__PURE__ */ BigInt(2 ** 32 - 1);
const _32n = /* @__PURE__ */ BigInt(32);
function fromBig(n, le = false) {
    if (le)
        return { h: Number(n & U32_MASK64), l: Number((n >> _32n) & U32_MASK64) };
    return { h: Number((n >> _32n) & U32_MASK64) | 0, l: Number(n & U32_MASK64) | 0 };
}
function split(lst, le = false) {
    const len = lst.length;
    let Ah = new Uint32Array(len);
    let Al = new Uint32Array(len);
    for (let i = 0; i < len; i++) {
        const { h, l } = fromBig(lst[i], le);
        [Ah[i], Al[i]] = [h, l];
    }
    return [Ah, Al];
}
const toBig = (h, l) => (BigInt(h >>> 0) << _32n) | BigInt(l >>> 0);
// for Shift in [0, 32)
const shrSH = (h, _l, s) => h >>> s;
const shrSL = (h, l, s) => (h << (32 - s)) | (l >>> s);
// Right rotate for Shift in [1, 32)
const rotrSH = (h, l, s) => (h >>> s) | (l << (32 - s));
const rotrSL = (h, l, s) => (h << (32 - s)) | (l >>> s);
// Right rotate for Shift in (32, 64), NOTE: 32 is special case.
const rotrBH = (h, l, s) => (h << (64 - s)) | (l >>> (s - 32));
const rotrBL = (h, l, s) => (h >>> (s - 32)) | (l << (64 - s));
// Right rotate for shift===32 (just swaps l&h)
const rotr32H = (_h, l) => l;
const rotr32L = (h, _l) => h;
// Left rotate for Shift in [1, 32)
const rotlSH = (h, l, s) => (h << s) | (l >>> (32 - s));
const rotlSL = (h, l, s) => (l << s) | (h >>> (32 - s));
// Left rotate for Shift in (32, 64), NOTE: 32 is special case.
const rotlBH = (h, l, s) => (l << (s - 32)) | (h >>> (64 - s));
const rotlBL = (h, l, s) => (h << (s - 32)) | (l >>> (64 - s));
// JS uses 32-bit signed integers for bitwise operations which means we cannot
// simple take carry out of low bit sum by shift, we need to use division.
function add(Ah, Al, Bh, Bl) {
    const l = (Al >>> 0) + (Bl >>> 0);
    return { h: (Ah + Bh + ((l / 2 ** 32) | 0)) | 0, l: l | 0 };
}
// Addition with more than 2 elements
const add3L = (Al, Bl, Cl) => (Al >>> 0) + (Bl >>> 0) + (Cl >>> 0);
const add3H = (low, Ah, Bh, Ch) => (Ah + Bh + Ch + ((low / 2 ** 32) | 0)) | 0;
const add4L = (Al, Bl, Cl, Dl) => (Al >>> 0) + (Bl >>> 0) + (Cl >>> 0) + (Dl >>> 0);
const add4H = (low, Ah, Bh, Ch, Dh) => (Ah + Bh + Ch + Dh + ((low / 2 ** 32) | 0)) | 0;
const add5L = (Al, Bl, Cl, Dl, El) => (Al >>> 0) + (Bl >>> 0) + (Cl >>> 0) + (Dl >>> 0) + (El >>> 0);
const add5H = (low, Ah, Bh, Ch, Dh, Eh) => (Ah + Bh + Ch + Dh + Eh + ((low / 2 ** 32) | 0)) | 0;
// prettier-ignore

// prettier-ignore
const u64 = {
    fromBig, split, toBig,
    shrSH, shrSL,
    rotrSH, rotrSL, rotrBH, rotrBL,
    rotr32H, rotr32L,
    rotlSH, rotlSL, rotlBH, rotlBL,
    add, add3L, add3H, add4L, add4H, add5H, add5L,
};
/* harmony default export */ const __WEBPACK_DEFAULT_EXPORT__ = (u64);
//# sourceMappingURL=_u64.js.map

/***/ }),

/***/ "./node_modules/@noble/hashes/esm/crypto.js":
/*!**************************************************!*\
  !*** ./node_modules/@noble/hashes/esm/crypto.js ***!
  \**************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   crypto: () => (/* binding */ crypto)
/* harmony export */ });
const crypto = typeof globalThis === 'object' && 'crypto' in globalThis ? globalThis.crypto : undefined;
//# sourceMappingURL=crypto.js.map

/***/ }),

/***/ "./node_modules/@noble/hashes/esm/hmac.js":
/*!************************************************!*\
  !*** ./node_modules/@noble/hashes/esm/hmac.js ***!
  \************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   HMAC: () => (/* binding */ HMAC),
/* harmony export */   hmac: () => (/* binding */ hmac)
/* harmony export */ });
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./utils.js */ "./node_modules/@noble/hashes/esm/utils.js");
/**
 * HMAC: RFC2104 message authentication code.
 * @module
 */

class HMAC extends _utils_js__WEBPACK_IMPORTED_MODULE_0__.Hash {
    constructor(hash, _key) {
        super();
        this.finished = false;
        this.destroyed = false;
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.ahash)(hash);
        const key = (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.toBytes)(_key);
        this.iHash = hash.create();
        if (typeof this.iHash.update !== 'function')
            throw new Error('Expected instance of class which extends utils.Hash');
        this.blockLen = this.iHash.blockLen;
        this.outputLen = this.iHash.outputLen;
        const blockLen = this.blockLen;
        const pad = new Uint8Array(blockLen);
        // blockLen can be bigger than outputLen
        pad.set(key.length > blockLen ? hash.create().update(key).digest() : key);
        for (let i = 0; i < pad.length; i++)
            pad[i] ^= 0x36;
        this.iHash.update(pad);
        // By doing update (processing of first block) of outer hash here we can re-use it between multiple calls via clone
        this.oHash = hash.create();
        // Undo internal XOR && apply outer XOR
        for (let i = 0; i < pad.length; i++)
            pad[i] ^= 0x36 ^ 0x5c;
        this.oHash.update(pad);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.clean)(pad);
    }
    update(buf) {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aexists)(this);
        this.iHash.update(buf);
        return this;
    }
    digestInto(out) {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.aexists)(this);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_0__.abytes)(out, this.outputLen);
        this.finished = true;
        this.iHash.digestInto(out);
        this.oHash.update(out);
        this.oHash.digestInto(out);
        this.destroy();
    }
    digest() {
        const out = new Uint8Array(this.oHash.outputLen);
        this.digestInto(out);
        return out;
    }
    _cloneInto(to) {
        // Create new instance without calling constructor since key already in state and we don't know it.
        to || (to = Object.create(Object.getPrototypeOf(this), {}));
        const { oHash, iHash, finished, destroyed, blockLen, outputLen } = this;
        to = to;
        to.finished = finished;
        to.destroyed = destroyed;
        to.blockLen = blockLen;
        to.outputLen = outputLen;
        to.oHash = oHash._cloneInto(to.oHash);
        to.iHash = iHash._cloneInto(to.iHash);
        return to;
    }
    clone() {
        return this._cloneInto();
    }
    destroy() {
        this.destroyed = true;
        this.oHash.destroy();
        this.iHash.destroy();
    }
}
/**
 * HMAC: RFC2104 message authentication code.
 * @param hash - function that would be used e.g. sha256
 * @param key - message key
 * @param message - message data
 * @example
 * import { hmac } from '@noble/hashes/hmac';
 * import { sha256 } from '@noble/hashes/sha2';
 * const mac1 = hmac(sha256, 'key', 'message');
 */
const hmac = (hash, key, message) => new HMAC(hash, key).update(message).digest();
hmac.create = (hash, key) => new HMAC(hash, key);
//# sourceMappingURL=hmac.js.map

/***/ }),

/***/ "./node_modules/@noble/hashes/esm/legacy.js":
/*!**************************************************!*\
  !*** ./node_modules/@noble/hashes/esm/legacy.js ***!
  \**************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   MD5: () => (/* binding */ MD5),
/* harmony export */   RIPEMD160: () => (/* binding */ RIPEMD160),
/* harmony export */   SHA1: () => (/* binding */ SHA1),
/* harmony export */   md5: () => (/* binding */ md5),
/* harmony export */   ripemd160: () => (/* binding */ ripemd160),
/* harmony export */   sha1: () => (/* binding */ sha1)
/* harmony export */ });
/* harmony import */ var _md_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./_md.js */ "./node_modules/@noble/hashes/esm/_md.js");
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ./utils.js */ "./node_modules/@noble/hashes/esm/utils.js");
/**

SHA1 (RFC 3174), MD5 (RFC 1321) and RIPEMD160 (RFC 2286) legacy, weak hash functions.
Don't use them in a new protocol. What "weak" means:

- Collisions can be made with 2^18 effort in MD5, 2^60 in SHA1, 2^80 in RIPEMD160.
- No practical pre-image attacks (only theoretical, 2^123.4)
- HMAC seems kinda ok: https://datatracker.ietf.org/doc/html/rfc6151
 * @module
 */


/** Initial SHA1 state */
const SHA1_IV = /* @__PURE__ */ Uint32Array.from([
    0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476, 0xc3d2e1f0,
]);
// Reusable temporary buffer
const SHA1_W = /* @__PURE__ */ new Uint32Array(80);
/** SHA1 legacy hash class. */
class SHA1 extends _md_js__WEBPACK_IMPORTED_MODULE_0__.HashMD {
    constructor() {
        super(64, 20, 8, false);
        this.A = SHA1_IV[0] | 0;
        this.B = SHA1_IV[1] | 0;
        this.C = SHA1_IV[2] | 0;
        this.D = SHA1_IV[3] | 0;
        this.E = SHA1_IV[4] | 0;
    }
    get() {
        const { A, B, C, D, E } = this;
        return [A, B, C, D, E];
    }
    set(A, B, C, D, E) {
        this.A = A | 0;
        this.B = B | 0;
        this.C = C | 0;
        this.D = D | 0;
        this.E = E | 0;
    }
    process(view, offset) {
        for (let i = 0; i < 16; i++, offset += 4)
            SHA1_W[i] = view.getUint32(offset, false);
        for (let i = 16; i < 80; i++)
            SHA1_W[i] = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.rotl)(SHA1_W[i - 3] ^ SHA1_W[i - 8] ^ SHA1_W[i - 14] ^ SHA1_W[i - 16], 1);
        // Compression function main loop, 80 rounds
        let { A, B, C, D, E } = this;
        for (let i = 0; i < 80; i++) {
            let F, K;
            if (i < 20) {
                F = (0,_md_js__WEBPACK_IMPORTED_MODULE_0__.Chi)(B, C, D);
                K = 0x5a827999;
            }
            else if (i < 40) {
                F = B ^ C ^ D;
                K = 0x6ed9eba1;
            }
            else if (i < 60) {
                F = (0,_md_js__WEBPACK_IMPORTED_MODULE_0__.Maj)(B, C, D);
                K = 0x8f1bbcdc;
            }
            else {
                F = B ^ C ^ D;
                K = 0xca62c1d6;
            }
            const T = ((0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.rotl)(A, 5) + F + E + K + SHA1_W[i]) | 0;
            E = D;
            D = C;
            C = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.rotl)(B, 30);
            B = A;
            A = T;
        }
        // Add the compressed chunk to the current hash value
        A = (A + this.A) | 0;
        B = (B + this.B) | 0;
        C = (C + this.C) | 0;
        D = (D + this.D) | 0;
        E = (E + this.E) | 0;
        this.set(A, B, C, D, E);
    }
    roundClean() {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(SHA1_W);
    }
    destroy() {
        this.set(0, 0, 0, 0, 0);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(this.buffer);
    }
}
/** SHA1 (RFC 3174) legacy hash function. It was cryptographically broken. */
const sha1 = /* @__PURE__ */ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.createHasher)(() => new SHA1());
/** Per-round constants */
const p32 = /* @__PURE__ */ Math.pow(2, 32);
const K = /* @__PURE__ */ Array.from({ length: 64 }, (_, i) => Math.floor(p32 * Math.abs(Math.sin(i + 1))));
/** md5 initial state: same as sha1, but 4 u32 instead of 5. */
const MD5_IV = /* @__PURE__ */ SHA1_IV.slice(0, 4);
// Reusable temporary buffer
const MD5_W = /* @__PURE__ */ new Uint32Array(16);
/** MD5 legacy hash class. */
class MD5 extends _md_js__WEBPACK_IMPORTED_MODULE_0__.HashMD {
    constructor() {
        super(64, 16, 8, true);
        this.A = MD5_IV[0] | 0;
        this.B = MD5_IV[1] | 0;
        this.C = MD5_IV[2] | 0;
        this.D = MD5_IV[3] | 0;
    }
    get() {
        const { A, B, C, D } = this;
        return [A, B, C, D];
    }
    set(A, B, C, D) {
        this.A = A | 0;
        this.B = B | 0;
        this.C = C | 0;
        this.D = D | 0;
    }
    process(view, offset) {
        for (let i = 0; i < 16; i++, offset += 4)
            MD5_W[i] = view.getUint32(offset, true);
        // Compression function main loop, 64 rounds
        let { A, B, C, D } = this;
        for (let i = 0; i < 64; i++) {
            let F, g, s;
            if (i < 16) {
                F = (0,_md_js__WEBPACK_IMPORTED_MODULE_0__.Chi)(B, C, D);
                g = i;
                s = [7, 12, 17, 22];
            }
            else if (i < 32) {
                F = (0,_md_js__WEBPACK_IMPORTED_MODULE_0__.Chi)(D, B, C);
                g = (5 * i + 1) % 16;
                s = [5, 9, 14, 20];
            }
            else if (i < 48) {
                F = B ^ C ^ D;
                g = (3 * i + 5) % 16;
                s = [4, 11, 16, 23];
            }
            else {
                F = C ^ (B | ~D);
                g = (7 * i) % 16;
                s = [6, 10, 15, 21];
            }
            F = F + A + K[i] + MD5_W[g];
            A = D;
            D = C;
            C = B;
            B = B + (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.rotl)(F, s[i % 4]);
        }
        // Add the compressed chunk to the current hash value
        A = (A + this.A) | 0;
        B = (B + this.B) | 0;
        C = (C + this.C) | 0;
        D = (D + this.D) | 0;
        this.set(A, B, C, D);
    }
    roundClean() {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(MD5_W);
    }
    destroy() {
        this.set(0, 0, 0, 0);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(this.buffer);
    }
}
/**
 * MD5 (RFC 1321) legacy hash function. It was cryptographically broken.
 * MD5 architecture is similar to SHA1, with some differences:
 * - Reduced output length: 16 bytes (128 bit) instead of 20
 * - 64 rounds, instead of 80
 * - Little-endian: could be faster, but will require more code
 * - Non-linear index selection: huge speed-up for unroll
 * - Per round constants: more memory accesses, additional speed-up for unroll
 */
const md5 = /* @__PURE__ */ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.createHasher)(() => new MD5());
// RIPEMD-160
const Rho160 = /* @__PURE__ */ Uint8Array.from([
    7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8,
]);
const Id160 = /* @__PURE__ */ (() => Uint8Array.from(new Array(16).fill(0).map((_, i) => i)))();
const Pi160 = /* @__PURE__ */ (() => Id160.map((i) => (9 * i + 5) % 16))();
const idxLR = /* @__PURE__ */ (() => {
    const L = [Id160];
    const R = [Pi160];
    const res = [L, R];
    for (let i = 0; i < 4; i++)
        for (let j of res)
            j.push(j[i].map((k) => Rho160[k]));
    return res;
})();
const idxL = /* @__PURE__ */ (() => idxLR[0])();
const idxR = /* @__PURE__ */ (() => idxLR[1])();
// const [idxL, idxR] = idxLR;
const shifts160 = /* @__PURE__ */ [
    [11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8],
    [12, 13, 11, 15, 6, 9, 9, 7, 12, 15, 11, 13, 7, 8, 7, 7],
    [13, 15, 14, 11, 7, 7, 6, 8, 13, 14, 13, 12, 5, 5, 6, 9],
    [14, 11, 12, 14, 8, 6, 5, 5, 15, 12, 15, 14, 9, 9, 8, 6],
    [15, 12, 13, 13, 9, 5, 8, 6, 14, 11, 12, 11, 8, 6, 5, 5],
].map((i) => Uint8Array.from(i));
const shiftsL160 = /* @__PURE__ */ idxL.map((idx, i) => idx.map((j) => shifts160[i][j]));
const shiftsR160 = /* @__PURE__ */ idxR.map((idx, i) => idx.map((j) => shifts160[i][j]));
const Kl160 = /* @__PURE__ */ Uint32Array.from([
    0x00000000, 0x5a827999, 0x6ed9eba1, 0x8f1bbcdc, 0xa953fd4e,
]);
const Kr160 = /* @__PURE__ */ Uint32Array.from([
    0x50a28be6, 0x5c4dd124, 0x6d703ef3, 0x7a6d76e9, 0x00000000,
]);
// It's called f() in spec.
function ripemd_f(group, x, y, z) {
    if (group === 0)
        return x ^ y ^ z;
    if (group === 1)
        return (x & y) | (~x & z);
    if (group === 2)
        return (x | ~y) ^ z;
    if (group === 3)
        return (x & z) | (y & ~z);
    return x ^ (y | ~z);
}
// Reusable temporary buffer
const BUF_160 = /* @__PURE__ */ new Uint32Array(16);
class RIPEMD160 extends _md_js__WEBPACK_IMPORTED_MODULE_0__.HashMD {
    constructor() {
        super(64, 20, 8, true);
        this.h0 = 0x67452301 | 0;
        this.h1 = 0xefcdab89 | 0;
        this.h2 = 0x98badcfe | 0;
        this.h3 = 0x10325476 | 0;
        this.h4 = 0xc3d2e1f0 | 0;
    }
    get() {
        const { h0, h1, h2, h3, h4 } = this;
        return [h0, h1, h2, h3, h4];
    }
    set(h0, h1, h2, h3, h4) {
        this.h0 = h0 | 0;
        this.h1 = h1 | 0;
        this.h2 = h2 | 0;
        this.h3 = h3 | 0;
        this.h4 = h4 | 0;
    }
    process(view, offset) {
        for (let i = 0; i < 16; i++, offset += 4)
            BUF_160[i] = view.getUint32(offset, true);
        // prettier-ignore
        let al = this.h0 | 0, ar = al, bl = this.h1 | 0, br = bl, cl = this.h2 | 0, cr = cl, dl = this.h3 | 0, dr = dl, el = this.h4 | 0, er = el;
        // Instead of iterating 0 to 80, we split it into 5 groups
        // And use the groups in constants, functions, etc. Much simpler
        for (let group = 0; group < 5; group++) {
            const rGroup = 4 - group;
            const hbl = Kl160[group], hbr = Kr160[group]; // prettier-ignore
            const rl = idxL[group], rr = idxR[group]; // prettier-ignore
            const sl = shiftsL160[group], sr = shiftsR160[group]; // prettier-ignore
            for (let i = 0; i < 16; i++) {
                const tl = ((0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.rotl)(al + ripemd_f(group, bl, cl, dl) + BUF_160[rl[i]] + hbl, sl[i]) + el) | 0;
                al = el, el = dl, dl = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.rotl)(cl, 10) | 0, cl = bl, bl = tl; // prettier-ignore
            }
            // 2 loops are 10% faster
            for (let i = 0; i < 16; i++) {
                const tr = ((0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.rotl)(ar + ripemd_f(rGroup, br, cr, dr) + BUF_160[rr[i]] + hbr, sr[i]) + er) | 0;
                ar = er, er = dr, dr = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.rotl)(cr, 10) | 0, cr = br, br = tr; // prettier-ignore
            }
        }
        // Add the compressed chunk to the current hash value
        this.set((this.h1 + cl + dr) | 0, (this.h2 + dl + er) | 0, (this.h3 + el + ar) | 0, (this.h4 + al + br) | 0, (this.h0 + bl + cr) | 0);
    }
    roundClean() {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(BUF_160);
    }
    destroy() {
        this.destroyed = true;
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(this.buffer);
        this.set(0, 0, 0, 0, 0);
    }
}
/**
 * RIPEMD-160 - a legacy hash function from 1990s.
 * * https://homes.esat.kuleuven.be/~bosselae/ripemd160.html
 * * https://homes.esat.kuleuven.be/~bosselae/ripemd160/pdf/AB-9601/AB-9601.pdf
 */
const ripemd160 = /* @__PURE__ */ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.createHasher)(() => new RIPEMD160());
//# sourceMappingURL=legacy.js.map

/***/ }),

/***/ "./node_modules/@noble/hashes/esm/ripemd160.js":
/*!*****************************************************!*\
  !*** ./node_modules/@noble/hashes/esm/ripemd160.js ***!
  \*****************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   RIPEMD160: () => (/* binding */ RIPEMD160),
/* harmony export */   ripemd160: () => (/* binding */ ripemd160)
/* harmony export */ });
/* harmony import */ var _legacy_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./legacy.js */ "./node_modules/@noble/hashes/esm/legacy.js");
/**
 * RIPEMD-160 legacy hash function.
 * https://homes.esat.kuleuven.be/~bosselae/ripemd160.html
 * https://homes.esat.kuleuven.be/~bosselae/ripemd160/pdf/AB-9601/AB-9601.pdf
 * @module
 * @deprecated
 */

/** @deprecated Use import from `noble/hashes/legacy` module */
const RIPEMD160 = _legacy_js__WEBPACK_IMPORTED_MODULE_0__.RIPEMD160;
/** @deprecated Use import from `noble/hashes/legacy` module */
const ripemd160 = _legacy_js__WEBPACK_IMPORTED_MODULE_0__.ripemd160;
//# sourceMappingURL=ripemd160.js.map

/***/ }),

/***/ "./node_modules/@noble/hashes/esm/sha2.js":
/*!************************************************!*\
  !*** ./node_modules/@noble/hashes/esm/sha2.js ***!
  \************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   SHA224: () => (/* binding */ SHA224),
/* harmony export */   SHA256: () => (/* binding */ SHA256),
/* harmony export */   SHA384: () => (/* binding */ SHA384),
/* harmony export */   SHA512: () => (/* binding */ SHA512),
/* harmony export */   SHA512_224: () => (/* binding */ SHA512_224),
/* harmony export */   SHA512_256: () => (/* binding */ SHA512_256),
/* harmony export */   sha224: () => (/* binding */ sha224),
/* harmony export */   sha256: () => (/* binding */ sha256),
/* harmony export */   sha384: () => (/* binding */ sha384),
/* harmony export */   sha512: () => (/* binding */ sha512),
/* harmony export */   sha512_224: () => (/* binding */ sha512_224),
/* harmony export */   sha512_256: () => (/* binding */ sha512_256)
/* harmony export */ });
/* harmony import */ var _md_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./_md.js */ "./node_modules/@noble/hashes/esm/_md.js");
/* harmony import */ var _u64_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ./_u64.js */ "./node_modules/@noble/hashes/esm/_u64.js");
/* harmony import */ var _utils_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ./utils.js */ "./node_modules/@noble/hashes/esm/utils.js");
/**
 * SHA2 hash function. A.k.a. sha256, sha384, sha512, sha512_224, sha512_256.
 * SHA256 is the fastest hash implementable in JS, even faster than Blake3.
 * Check out [RFC 4634](https://datatracker.ietf.org/doc/html/rfc4634) and
 * [FIPS 180-4](https://nvlpubs.nist.gov/nistpubs/FIPS/NIST.FIPS.180-4.pdf).
 * @module
 */



/**
 * Round constants:
 * First 32 bits of fractional parts of the cube roots of the first 64 primes 2..311)
 */
// prettier-ignore
const SHA256_K = /* @__PURE__ */ Uint32Array.from([
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
]);
/** Reusable temporary buffer. "W" comes straight from spec. */
const SHA256_W = /* @__PURE__ */ new Uint32Array(64);
class SHA256 extends _md_js__WEBPACK_IMPORTED_MODULE_0__.HashMD {
    constructor(outputLen = 32) {
        super(64, outputLen, 8, false);
        // We cannot use array here since array allows indexing by variable
        // which means optimizer/compiler cannot use registers.
        this.A = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA256_IV[0] | 0;
        this.B = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA256_IV[1] | 0;
        this.C = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA256_IV[2] | 0;
        this.D = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA256_IV[3] | 0;
        this.E = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA256_IV[4] | 0;
        this.F = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA256_IV[5] | 0;
        this.G = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA256_IV[6] | 0;
        this.H = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA256_IV[7] | 0;
    }
    get() {
        const { A, B, C, D, E, F, G, H } = this;
        return [A, B, C, D, E, F, G, H];
    }
    // prettier-ignore
    set(A, B, C, D, E, F, G, H) {
        this.A = A | 0;
        this.B = B | 0;
        this.C = C | 0;
        this.D = D | 0;
        this.E = E | 0;
        this.F = F | 0;
        this.G = G | 0;
        this.H = H | 0;
    }
    process(view, offset) {
        // Extend the first 16 words into the remaining 48 words w[16..63] of the message schedule array
        for (let i = 0; i < 16; i++, offset += 4)
            SHA256_W[i] = view.getUint32(offset, false);
        for (let i = 16; i < 64; i++) {
            const W15 = SHA256_W[i - 15];
            const W2 = SHA256_W[i - 2];
            const s0 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.rotr)(W15, 7) ^ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.rotr)(W15, 18) ^ (W15 >>> 3);
            const s1 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.rotr)(W2, 17) ^ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.rotr)(W2, 19) ^ (W2 >>> 10);
            SHA256_W[i] = (s1 + SHA256_W[i - 7] + s0 + SHA256_W[i - 16]) | 0;
        }
        // Compression function main loop, 64 rounds
        let { A, B, C, D, E, F, G, H } = this;
        for (let i = 0; i < 64; i++) {
            const sigma1 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.rotr)(E, 6) ^ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.rotr)(E, 11) ^ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.rotr)(E, 25);
            const T1 = (H + sigma1 + (0,_md_js__WEBPACK_IMPORTED_MODULE_0__.Chi)(E, F, G) + SHA256_K[i] + SHA256_W[i]) | 0;
            const sigma0 = (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.rotr)(A, 2) ^ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.rotr)(A, 13) ^ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.rotr)(A, 22);
            const T2 = (sigma0 + (0,_md_js__WEBPACK_IMPORTED_MODULE_0__.Maj)(A, B, C)) | 0;
            H = G;
            G = F;
            F = E;
            E = (D + T1) | 0;
            D = C;
            C = B;
            B = A;
            A = (T1 + T2) | 0;
        }
        // Add the compressed chunk to the current hash value
        A = (A + this.A) | 0;
        B = (B + this.B) | 0;
        C = (C + this.C) | 0;
        D = (D + this.D) | 0;
        E = (E + this.E) | 0;
        F = (F + this.F) | 0;
        G = (G + this.G) | 0;
        H = (H + this.H) | 0;
        this.set(A, B, C, D, E, F, G, H);
    }
    roundClean() {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(SHA256_W);
    }
    destroy() {
        this.set(0, 0, 0, 0, 0, 0, 0, 0);
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(this.buffer);
    }
}
class SHA224 extends SHA256 {
    constructor() {
        super(28);
        this.A = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA224_IV[0] | 0;
        this.B = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA224_IV[1] | 0;
        this.C = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA224_IV[2] | 0;
        this.D = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA224_IV[3] | 0;
        this.E = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA224_IV[4] | 0;
        this.F = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA224_IV[5] | 0;
        this.G = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA224_IV[6] | 0;
        this.H = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA224_IV[7] | 0;
    }
}
// SHA2-512 is slower than sha256 in js because u64 operations are slow.
// Round contants
// First 32 bits of the fractional parts of the cube roots of the first 80 primes 2..409
// prettier-ignore
const K512 = /* @__PURE__ */ (() => _u64_js__WEBPACK_IMPORTED_MODULE_2__.split([
    '0x428a2f98d728ae22', '0x7137449123ef65cd', '0xb5c0fbcfec4d3b2f', '0xe9b5dba58189dbbc',
    '0x3956c25bf348b538', '0x59f111f1b605d019', '0x923f82a4af194f9b', '0xab1c5ed5da6d8118',
    '0xd807aa98a3030242', '0x12835b0145706fbe', '0x243185be4ee4b28c', '0x550c7dc3d5ffb4e2',
    '0x72be5d74f27b896f', '0x80deb1fe3b1696b1', '0x9bdc06a725c71235', '0xc19bf174cf692694',
    '0xe49b69c19ef14ad2', '0xefbe4786384f25e3', '0x0fc19dc68b8cd5b5', '0x240ca1cc77ac9c65',
    '0x2de92c6f592b0275', '0x4a7484aa6ea6e483', '0x5cb0a9dcbd41fbd4', '0x76f988da831153b5',
    '0x983e5152ee66dfab', '0xa831c66d2db43210', '0xb00327c898fb213f', '0xbf597fc7beef0ee4',
    '0xc6e00bf33da88fc2', '0xd5a79147930aa725', '0x06ca6351e003826f', '0x142929670a0e6e70',
    '0x27b70a8546d22ffc', '0x2e1b21385c26c926', '0x4d2c6dfc5ac42aed', '0x53380d139d95b3df',
    '0x650a73548baf63de', '0x766a0abb3c77b2a8', '0x81c2c92e47edaee6', '0x92722c851482353b',
    '0xa2bfe8a14cf10364', '0xa81a664bbc423001', '0xc24b8b70d0f89791', '0xc76c51a30654be30',
    '0xd192e819d6ef5218', '0xd69906245565a910', '0xf40e35855771202a', '0x106aa07032bbd1b8',
    '0x19a4c116b8d2d0c8', '0x1e376c085141ab53', '0x2748774cdf8eeb99', '0x34b0bcb5e19b48a8',
    '0x391c0cb3c5c95a63', '0x4ed8aa4ae3418acb', '0x5b9cca4f7763e373', '0x682e6ff3d6b2b8a3',
    '0x748f82ee5defb2fc', '0x78a5636f43172f60', '0x84c87814a1f0ab72', '0x8cc702081a6439ec',
    '0x90befffa23631e28', '0xa4506cebde82bde9', '0xbef9a3f7b2c67915', '0xc67178f2e372532b',
    '0xca273eceea26619c', '0xd186b8c721c0c207', '0xeada7dd6cde0eb1e', '0xf57d4f7fee6ed178',
    '0x06f067aa72176fba', '0x0a637dc5a2c898a6', '0x113f9804bef90dae', '0x1b710b35131c471b',
    '0x28db77f523047d84', '0x32caab7b40c72493', '0x3c9ebe0a15c9bebc', '0x431d67c49c100d4c',
    '0x4cc5d4becb3e42b6', '0x597f299cfc657e2a', '0x5fcb6fab3ad6faec', '0x6c44198c4a475817'
].map(n => BigInt(n))))();
const SHA512_Kh = /* @__PURE__ */ (() => K512[0])();
const SHA512_Kl = /* @__PURE__ */ (() => K512[1])();
// Reusable temporary buffers
const SHA512_W_H = /* @__PURE__ */ new Uint32Array(80);
const SHA512_W_L = /* @__PURE__ */ new Uint32Array(80);
class SHA512 extends _md_js__WEBPACK_IMPORTED_MODULE_0__.HashMD {
    constructor(outputLen = 64) {
        super(128, outputLen, 16, false);
        // We cannot use array here since array allows indexing by variable
        // which means optimizer/compiler cannot use registers.
        // h -- high 32 bits, l -- low 32 bits
        this.Ah = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[0] | 0;
        this.Al = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[1] | 0;
        this.Bh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[2] | 0;
        this.Bl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[3] | 0;
        this.Ch = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[4] | 0;
        this.Cl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[5] | 0;
        this.Dh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[6] | 0;
        this.Dl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[7] | 0;
        this.Eh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[8] | 0;
        this.El = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[9] | 0;
        this.Fh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[10] | 0;
        this.Fl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[11] | 0;
        this.Gh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[12] | 0;
        this.Gl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[13] | 0;
        this.Hh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[14] | 0;
        this.Hl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_IV[15] | 0;
    }
    // prettier-ignore
    get() {
        const { Ah, Al, Bh, Bl, Ch, Cl, Dh, Dl, Eh, El, Fh, Fl, Gh, Gl, Hh, Hl } = this;
        return [Ah, Al, Bh, Bl, Ch, Cl, Dh, Dl, Eh, El, Fh, Fl, Gh, Gl, Hh, Hl];
    }
    // prettier-ignore
    set(Ah, Al, Bh, Bl, Ch, Cl, Dh, Dl, Eh, El, Fh, Fl, Gh, Gl, Hh, Hl) {
        this.Ah = Ah | 0;
        this.Al = Al | 0;
        this.Bh = Bh | 0;
        this.Bl = Bl | 0;
        this.Ch = Ch | 0;
        this.Cl = Cl | 0;
        this.Dh = Dh | 0;
        this.Dl = Dl | 0;
        this.Eh = Eh | 0;
        this.El = El | 0;
        this.Fh = Fh | 0;
        this.Fl = Fl | 0;
        this.Gh = Gh | 0;
        this.Gl = Gl | 0;
        this.Hh = Hh | 0;
        this.Hl = Hl | 0;
    }
    process(view, offset) {
        // Extend the first 16 words into the remaining 64 words w[16..79] of the message schedule array
        for (let i = 0; i < 16; i++, offset += 4) {
            SHA512_W_H[i] = view.getUint32(offset);
            SHA512_W_L[i] = view.getUint32((offset += 4));
        }
        for (let i = 16; i < 80; i++) {
            // s0 := (w[i-15] rightrotate 1) xor (w[i-15] rightrotate 8) xor (w[i-15] rightshift 7)
            const W15h = SHA512_W_H[i - 15] | 0;
            const W15l = SHA512_W_L[i - 15] | 0;
            const s0h = _u64_js__WEBPACK_IMPORTED_MODULE_2__.rotrSH(W15h, W15l, 1) ^ _u64_js__WEBPACK_IMPORTED_MODULE_2__.rotrSH(W15h, W15l, 8) ^ _u64_js__WEBPACK_IMPORTED_MODULE_2__.shrSH(W15h, W15l, 7);
            const s0l = _u64_js__WEBPACK_IMPORTED_MODULE_2__.rotrSL(W15h, W15l, 1) ^ _u64_js__WEBPACK_IMPORTED_MODULE_2__.rotrSL(W15h, W15l, 8) ^ _u64_js__WEBPACK_IMPORTED_MODULE_2__.shrSL(W15h, W15l, 7);
            // s1 := (w[i-2] rightrotate 19) xor (w[i-2] rightrotate 61) xor (w[i-2] rightshift 6)
            const W2h = SHA512_W_H[i - 2] | 0;
            const W2l = SHA512_W_L[i - 2] | 0;
            const s1h = _u64_js__WEBPACK_IMPORTED_MODULE_2__.rotrSH(W2h, W2l, 19) ^ _u64_js__WEBPACK_IMPORTED_MODULE_2__.rotrBH(W2h, W2l, 61) ^ _u64_js__WEBPACK_IMPORTED_MODULE_2__.shrSH(W2h, W2l, 6);
            const s1l = _u64_js__WEBPACK_IMPORTED_MODULE_2__.rotrSL(W2h, W2l, 19) ^ _u64_js__WEBPACK_IMPORTED_MODULE_2__.rotrBL(W2h, W2l, 61) ^ _u64_js__WEBPACK_IMPORTED_MODULE_2__.shrSL(W2h, W2l, 6);
            // SHA256_W[i] = s0 + s1 + SHA256_W[i - 7] + SHA256_W[i - 16];
            const SUMl = _u64_js__WEBPACK_IMPORTED_MODULE_2__.add4L(s0l, s1l, SHA512_W_L[i - 7], SHA512_W_L[i - 16]);
            const SUMh = _u64_js__WEBPACK_IMPORTED_MODULE_2__.add4H(SUMl, s0h, s1h, SHA512_W_H[i - 7], SHA512_W_H[i - 16]);
            SHA512_W_H[i] = SUMh | 0;
            SHA512_W_L[i] = SUMl | 0;
        }
        let { Ah, Al, Bh, Bl, Ch, Cl, Dh, Dl, Eh, El, Fh, Fl, Gh, Gl, Hh, Hl } = this;
        // Compression function main loop, 80 rounds
        for (let i = 0; i < 80; i++) {
            // S1 := (e rightrotate 14) xor (e rightrotate 18) xor (e rightrotate 41)
            const sigma1h = _u64_js__WEBPACK_IMPORTED_MODULE_2__.rotrSH(Eh, El, 14) ^ _u64_js__WEBPACK_IMPORTED_MODULE_2__.rotrSH(Eh, El, 18) ^ _u64_js__WEBPACK_IMPORTED_MODULE_2__.rotrBH(Eh, El, 41);
            const sigma1l = _u64_js__WEBPACK_IMPORTED_MODULE_2__.rotrSL(Eh, El, 14) ^ _u64_js__WEBPACK_IMPORTED_MODULE_2__.rotrSL(Eh, El, 18) ^ _u64_js__WEBPACK_IMPORTED_MODULE_2__.rotrBL(Eh, El, 41);
            //const T1 = (H + sigma1 + Chi(E, F, G) + SHA256_K[i] + SHA256_W[i]) | 0;
            const CHIh = (Eh & Fh) ^ (~Eh & Gh);
            const CHIl = (El & Fl) ^ (~El & Gl);
            // T1 = H + sigma1 + Chi(E, F, G) + SHA512_K[i] + SHA512_W[i]
            // prettier-ignore
            const T1ll = _u64_js__WEBPACK_IMPORTED_MODULE_2__.add5L(Hl, sigma1l, CHIl, SHA512_Kl[i], SHA512_W_L[i]);
            const T1h = _u64_js__WEBPACK_IMPORTED_MODULE_2__.add5H(T1ll, Hh, sigma1h, CHIh, SHA512_Kh[i], SHA512_W_H[i]);
            const T1l = T1ll | 0;
            // S0 := (a rightrotate 28) xor (a rightrotate 34) xor (a rightrotate 39)
            const sigma0h = _u64_js__WEBPACK_IMPORTED_MODULE_2__.rotrSH(Ah, Al, 28) ^ _u64_js__WEBPACK_IMPORTED_MODULE_2__.rotrBH(Ah, Al, 34) ^ _u64_js__WEBPACK_IMPORTED_MODULE_2__.rotrBH(Ah, Al, 39);
            const sigma0l = _u64_js__WEBPACK_IMPORTED_MODULE_2__.rotrSL(Ah, Al, 28) ^ _u64_js__WEBPACK_IMPORTED_MODULE_2__.rotrBL(Ah, Al, 34) ^ _u64_js__WEBPACK_IMPORTED_MODULE_2__.rotrBL(Ah, Al, 39);
            const MAJh = (Ah & Bh) ^ (Ah & Ch) ^ (Bh & Ch);
            const MAJl = (Al & Bl) ^ (Al & Cl) ^ (Bl & Cl);
            Hh = Gh | 0;
            Hl = Gl | 0;
            Gh = Fh | 0;
            Gl = Fl | 0;
            Fh = Eh | 0;
            Fl = El | 0;
            ({ h: Eh, l: El } = _u64_js__WEBPACK_IMPORTED_MODULE_2__.add(Dh | 0, Dl | 0, T1h | 0, T1l | 0));
            Dh = Ch | 0;
            Dl = Cl | 0;
            Ch = Bh | 0;
            Cl = Bl | 0;
            Bh = Ah | 0;
            Bl = Al | 0;
            const All = _u64_js__WEBPACK_IMPORTED_MODULE_2__.add3L(T1l, sigma0l, MAJl);
            Ah = _u64_js__WEBPACK_IMPORTED_MODULE_2__.add3H(All, T1h, sigma0h, MAJh);
            Al = All | 0;
        }
        // Add the compressed chunk to the current hash value
        ({ h: Ah, l: Al } = _u64_js__WEBPACK_IMPORTED_MODULE_2__.add(this.Ah | 0, this.Al | 0, Ah | 0, Al | 0));
        ({ h: Bh, l: Bl } = _u64_js__WEBPACK_IMPORTED_MODULE_2__.add(this.Bh | 0, this.Bl | 0, Bh | 0, Bl | 0));
        ({ h: Ch, l: Cl } = _u64_js__WEBPACK_IMPORTED_MODULE_2__.add(this.Ch | 0, this.Cl | 0, Ch | 0, Cl | 0));
        ({ h: Dh, l: Dl } = _u64_js__WEBPACK_IMPORTED_MODULE_2__.add(this.Dh | 0, this.Dl | 0, Dh | 0, Dl | 0));
        ({ h: Eh, l: El } = _u64_js__WEBPACK_IMPORTED_MODULE_2__.add(this.Eh | 0, this.El | 0, Eh | 0, El | 0));
        ({ h: Fh, l: Fl } = _u64_js__WEBPACK_IMPORTED_MODULE_2__.add(this.Fh | 0, this.Fl | 0, Fh | 0, Fl | 0));
        ({ h: Gh, l: Gl } = _u64_js__WEBPACK_IMPORTED_MODULE_2__.add(this.Gh | 0, this.Gl | 0, Gh | 0, Gl | 0));
        ({ h: Hh, l: Hl } = _u64_js__WEBPACK_IMPORTED_MODULE_2__.add(this.Hh | 0, this.Hl | 0, Hh | 0, Hl | 0));
        this.set(Ah, Al, Bh, Bl, Ch, Cl, Dh, Dl, Eh, El, Fh, Fl, Gh, Gl, Hh, Hl);
    }
    roundClean() {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(SHA512_W_H, SHA512_W_L);
    }
    destroy() {
        (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.clean)(this.buffer);
        this.set(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
class SHA384 extends SHA512 {
    constructor() {
        super(48);
        this.Ah = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[0] | 0;
        this.Al = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[1] | 0;
        this.Bh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[2] | 0;
        this.Bl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[3] | 0;
        this.Ch = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[4] | 0;
        this.Cl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[5] | 0;
        this.Dh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[6] | 0;
        this.Dl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[7] | 0;
        this.Eh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[8] | 0;
        this.El = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[9] | 0;
        this.Fh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[10] | 0;
        this.Fl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[11] | 0;
        this.Gh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[12] | 0;
        this.Gl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[13] | 0;
        this.Hh = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[14] | 0;
        this.Hl = _md_js__WEBPACK_IMPORTED_MODULE_0__.SHA384_IV[15] | 0;
    }
}
/**
 * Truncated SHA512/256 and SHA512/224.
 * SHA512_IV is XORed with 0xa5a5a5a5a5a5a5a5, then used as "intermediary" IV of SHA512/t.
 * Then t hashes string to produce result IV.
 * See `test/misc/sha2-gen-iv.js`.
 */
/** SHA512/224 IV */
const T224_IV = /* @__PURE__ */ Uint32Array.from([
    0x8c3d37c8, 0x19544da2, 0x73e19966, 0x89dcd4d6, 0x1dfab7ae, 0x32ff9c82, 0x679dd514, 0x582f9fcf,
    0x0f6d2b69, 0x7bd44da8, 0x77e36f73, 0x04c48942, 0x3f9d85a8, 0x6a1d36c8, 0x1112e6ad, 0x91d692a1,
]);
/** SHA512/256 IV */
const T256_IV = /* @__PURE__ */ Uint32Array.from([
    0x22312194, 0xfc2bf72c, 0x9f555fa3, 0xc84c64c2, 0x2393b86b, 0x6f53b151, 0x96387719, 0x5940eabd,
    0x96283ee2, 0xa88effe3, 0xbe5e1e25, 0x53863992, 0x2b0199fc, 0x2c85b8aa, 0x0eb72ddc, 0x81c52ca2,
]);
class SHA512_224 extends SHA512 {
    constructor() {
        super(28);
        this.Ah = T224_IV[0] | 0;
        this.Al = T224_IV[1] | 0;
        this.Bh = T224_IV[2] | 0;
        this.Bl = T224_IV[3] | 0;
        this.Ch = T224_IV[4] | 0;
        this.Cl = T224_IV[5] | 0;
        this.Dh = T224_IV[6] | 0;
        this.Dl = T224_IV[7] | 0;
        this.Eh = T224_IV[8] | 0;
        this.El = T224_IV[9] | 0;
        this.Fh = T224_IV[10] | 0;
        this.Fl = T224_IV[11] | 0;
        this.Gh = T224_IV[12] | 0;
        this.Gl = T224_IV[13] | 0;
        this.Hh = T224_IV[14] | 0;
        this.Hl = T224_IV[15] | 0;
    }
}
class SHA512_256 extends SHA512 {
    constructor() {
        super(32);
        this.Ah = T256_IV[0] | 0;
        this.Al = T256_IV[1] | 0;
        this.Bh = T256_IV[2] | 0;
        this.Bl = T256_IV[3] | 0;
        this.Ch = T256_IV[4] | 0;
        this.Cl = T256_IV[5] | 0;
        this.Dh = T256_IV[6] | 0;
        this.Dl = T256_IV[7] | 0;
        this.Eh = T256_IV[8] | 0;
        this.El = T256_IV[9] | 0;
        this.Fh = T256_IV[10] | 0;
        this.Fl = T256_IV[11] | 0;
        this.Gh = T256_IV[12] | 0;
        this.Gl = T256_IV[13] | 0;
        this.Hh = T256_IV[14] | 0;
        this.Hl = T256_IV[15] | 0;
    }
}
/**
 * SHA2-256 hash function from RFC 4634.
 *
 * It is the fastest JS hash, even faster than Blake3.
 * To break sha256 using birthday attack, attackers need to try 2^128 hashes.
 * BTC network is doing 2^70 hashes/sec (2^95 hashes/year) as per 2025.
 */
const sha256 = /* @__PURE__ */ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.createHasher)(() => new SHA256());
/** SHA2-224 hash function from RFC 4634 */
const sha224 = /* @__PURE__ */ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.createHasher)(() => new SHA224());
/** SHA2-512 hash function from RFC 4634. */
const sha512 = /* @__PURE__ */ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.createHasher)(() => new SHA512());
/** SHA2-384 hash function from RFC 4634. */
const sha384 = /* @__PURE__ */ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.createHasher)(() => new SHA384());
/**
 * SHA2-512/256 "truncated" hash function, with improved resistance to length extension attacks.
 * See the paper on [truncated SHA512](https://eprint.iacr.org/2010/548.pdf).
 */
const sha512_256 = /* @__PURE__ */ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.createHasher)(() => new SHA512_256());
/**
 * SHA2-512/224 "truncated" hash function, with improved resistance to length extension attacks.
 * See the paper on [truncated SHA512](https://eprint.iacr.org/2010/548.pdf).
 */
const sha512_224 = /* @__PURE__ */ (0,_utils_js__WEBPACK_IMPORTED_MODULE_1__.createHasher)(() => new SHA512_224());
//# sourceMappingURL=sha2.js.map

/***/ }),

/***/ "./node_modules/@noble/hashes/esm/sha256.js":
/*!**************************************************!*\
  !*** ./node_modules/@noble/hashes/esm/sha256.js ***!
  \**************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   SHA224: () => (/* binding */ SHA224),
/* harmony export */   SHA256: () => (/* binding */ SHA256),
/* harmony export */   sha224: () => (/* binding */ sha224),
/* harmony export */   sha256: () => (/* binding */ sha256)
/* harmony export */ });
/* harmony import */ var _sha2_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./sha2.js */ "./node_modules/@noble/hashes/esm/sha2.js");
/**
 * SHA2-256 a.k.a. sha256. In JS, it is the fastest hash, even faster than Blake3.
 *
 * To break sha256 using birthday attack, attackers need to try 2^128 hashes.
 * BTC network is doing 2^70 hashes/sec (2^95 hashes/year) as per 2025.
 *
 * Check out [FIPS 180-4](https://nvlpubs.nist.gov/nistpubs/FIPS/NIST.FIPS.180-4.pdf).
 * @module
 * @deprecated
 */

/** @deprecated Use import from `noble/hashes/sha2` module */
const SHA256 = _sha2_js__WEBPACK_IMPORTED_MODULE_0__.SHA256;
/** @deprecated Use import from `noble/hashes/sha2` module */
const sha256 = _sha2_js__WEBPACK_IMPORTED_MODULE_0__.sha256;
/** @deprecated Use import from `noble/hashes/sha2` module */
const SHA224 = _sha2_js__WEBPACK_IMPORTED_MODULE_0__.SHA224;
/** @deprecated Use import from `noble/hashes/sha2` module */
const sha224 = _sha2_js__WEBPACK_IMPORTED_MODULE_0__.sha224;
//# sourceMappingURL=sha256.js.map

/***/ }),

/***/ "./node_modules/@noble/hashes/esm/sha512.js":
/*!**************************************************!*\
  !*** ./node_modules/@noble/hashes/esm/sha512.js ***!
  \**************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   SHA384: () => (/* binding */ SHA384),
/* harmony export */   SHA512: () => (/* binding */ SHA512),
/* harmony export */   SHA512_224: () => (/* binding */ SHA512_224),
/* harmony export */   SHA512_256: () => (/* binding */ SHA512_256),
/* harmony export */   sha384: () => (/* binding */ sha384),
/* harmony export */   sha512: () => (/* binding */ sha512),
/* harmony export */   sha512_224: () => (/* binding */ sha512_224),
/* harmony export */   sha512_256: () => (/* binding */ sha512_256)
/* harmony export */ });
/* harmony import */ var _sha2_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./sha2.js */ "./node_modules/@noble/hashes/esm/sha2.js");
/**
 * SHA2-512 a.k.a. sha512 and sha384. It is slower than sha256 in js because u64 operations are slow.
 *
 * Check out [RFC 4634](https://datatracker.ietf.org/doc/html/rfc4634) and
 * [the paper on truncated SHA512/256](https://eprint.iacr.org/2010/548.pdf).
 * @module
 * @deprecated
 */

/** @deprecated Use import from `noble/hashes/sha2` module */
const SHA512 = _sha2_js__WEBPACK_IMPORTED_MODULE_0__.SHA512;
/** @deprecated Use import from `noble/hashes/sha2` module */
const sha512 = _sha2_js__WEBPACK_IMPORTED_MODULE_0__.sha512;
/** @deprecated Use import from `noble/hashes/sha2` module */
const SHA384 = _sha2_js__WEBPACK_IMPORTED_MODULE_0__.SHA384;
/** @deprecated Use import from `noble/hashes/sha2` module */
const sha384 = _sha2_js__WEBPACK_IMPORTED_MODULE_0__.sha384;
/** @deprecated Use import from `noble/hashes/sha2` module */
const SHA512_224 = _sha2_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_224;
/** @deprecated Use import from `noble/hashes/sha2` module */
const sha512_224 = _sha2_js__WEBPACK_IMPORTED_MODULE_0__.sha512_224;
/** @deprecated Use import from `noble/hashes/sha2` module */
const SHA512_256 = _sha2_js__WEBPACK_IMPORTED_MODULE_0__.SHA512_256;
/** @deprecated Use import from `noble/hashes/sha2` module */
const sha512_256 = _sha2_js__WEBPACK_IMPORTED_MODULE_0__.sha512_256;
//# sourceMappingURL=sha512.js.map

/***/ }),

/***/ "./node_modules/@noble/hashes/esm/utils.js":
/*!*************************************************!*\
  !*** ./node_modules/@noble/hashes/esm/utils.js ***!
  \*************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   Hash: () => (/* binding */ Hash),
/* harmony export */   abytes: () => (/* binding */ abytes),
/* harmony export */   aexists: () => (/* binding */ aexists),
/* harmony export */   ahash: () => (/* binding */ ahash),
/* harmony export */   anumber: () => (/* binding */ anumber),
/* harmony export */   aoutput: () => (/* binding */ aoutput),
/* harmony export */   asyncLoop: () => (/* binding */ asyncLoop),
/* harmony export */   byteSwap: () => (/* binding */ byteSwap),
/* harmony export */   byteSwap32: () => (/* binding */ byteSwap32),
/* harmony export */   byteSwapIfBE: () => (/* binding */ byteSwapIfBE),
/* harmony export */   bytesToHex: () => (/* binding */ bytesToHex),
/* harmony export */   bytesToUtf8: () => (/* binding */ bytesToUtf8),
/* harmony export */   checkOpts: () => (/* binding */ checkOpts),
/* harmony export */   clean: () => (/* binding */ clean),
/* harmony export */   concatBytes: () => (/* binding */ concatBytes),
/* harmony export */   createHasher: () => (/* binding */ createHasher),
/* harmony export */   createOptHasher: () => (/* binding */ createOptHasher),
/* harmony export */   createView: () => (/* binding */ createView),
/* harmony export */   createXOFer: () => (/* binding */ createXOFer),
/* harmony export */   hexToBytes: () => (/* binding */ hexToBytes),
/* harmony export */   isBytes: () => (/* binding */ isBytes),
/* harmony export */   isLE: () => (/* binding */ isLE),
/* harmony export */   kdfInputToBytes: () => (/* binding */ kdfInputToBytes),
/* harmony export */   nextTick: () => (/* binding */ nextTick),
/* harmony export */   randomBytes: () => (/* binding */ randomBytes),
/* harmony export */   rotl: () => (/* binding */ rotl),
/* harmony export */   rotr: () => (/* binding */ rotr),
/* harmony export */   swap32IfBE: () => (/* binding */ swap32IfBE),
/* harmony export */   swap8IfBE: () => (/* binding */ swap8IfBE),
/* harmony export */   toBytes: () => (/* binding */ toBytes),
/* harmony export */   u32: () => (/* binding */ u32),
/* harmony export */   u8: () => (/* binding */ u8),
/* harmony export */   utf8ToBytes: () => (/* binding */ utf8ToBytes),
/* harmony export */   wrapConstructor: () => (/* binding */ wrapConstructor),
/* harmony export */   wrapConstructorWithOpts: () => (/* binding */ wrapConstructorWithOpts),
/* harmony export */   wrapXOFConstructorWithOpts: () => (/* binding */ wrapXOFConstructorWithOpts)
/* harmony export */ });
/* harmony import */ var _noble_hashes_crypto__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @noble/hashes/crypto */ "./node_modules/@noble/hashes/esm/crypto.js");
/**
 * Utilities for hex, bytes, CSPRNG.
 * @module
 */
/*! noble-hashes - MIT License (c) 2022 Paul Miller (paulmillr.com) */
// We use WebCrypto aka globalThis.crypto, which exists in browsers and node.js 16+.
// node.js versions earlier than v19 don't declare it in global scope.
// For node.js, package.json#exports field mapping rewrites import
// from `crypto` to `cryptoNode`, which imports native module.
// Makes the utils un-importable in browsers without a bundler.
// Once node.js 18 is deprecated (2025-04-30), we can just drop the import.

/** Checks if something is Uint8Array. Be careful: nodejs Buffer will return true. */
function isBytes(a) {
    return a instanceof Uint8Array || (ArrayBuffer.isView(a) && a.constructor.name === 'Uint8Array');
}
/** Asserts something is positive integer. */
function anumber(n) {
    if (!Number.isSafeInteger(n) || n < 0)
        throw new Error('positive integer expected, got ' + n);
}
/** Asserts something is Uint8Array. */
function abytes(b, ...lengths) {
    if (!isBytes(b))
        throw new Error('Uint8Array expected');
    if (lengths.length > 0 && !lengths.includes(b.length))
        throw new Error('Uint8Array expected of length ' + lengths + ', got length=' + b.length);
}
/** Asserts something is hash */
function ahash(h) {
    if (typeof h !== 'function' || typeof h.create !== 'function')
        throw new Error('Hash should be wrapped by utils.createHasher');
    anumber(h.outputLen);
    anumber(h.blockLen);
}
/** Asserts a hash instance has not been destroyed / finished */
function aexists(instance, checkFinished = true) {
    if (instance.destroyed)
        throw new Error('Hash instance has been destroyed');
    if (checkFinished && instance.finished)
        throw new Error('Hash#digest() has already been called');
}
/** Asserts output is properly-sized byte array */
function aoutput(out, instance) {
    abytes(out);
    const min = instance.outputLen;
    if (out.length < min) {
        throw new Error('digestInto() expects output buffer of length at least ' + min);
    }
}
/** Cast u8 / u16 / u32 to u8. */
function u8(arr) {
    return new Uint8Array(arr.buffer, arr.byteOffset, arr.byteLength);
}
/** Cast u8 / u16 / u32 to u32. */
function u32(arr) {
    return new Uint32Array(arr.buffer, arr.byteOffset, Math.floor(arr.byteLength / 4));
}
/** Zeroize a byte array. Warning: JS provides no guarantees. */
function clean(...arrays) {
    for (let i = 0; i < arrays.length; i++) {
        arrays[i].fill(0);
    }
}
/** Create DataView of an array for easy byte-level manipulation. */
function createView(arr) {
    return new DataView(arr.buffer, arr.byteOffset, arr.byteLength);
}
/** The rotate right (circular right shift) operation for uint32 */
function rotr(word, shift) {
    return (word << (32 - shift)) | (word >>> shift);
}
/** The rotate left (circular left shift) operation for uint32 */
function rotl(word, shift) {
    return (word << shift) | ((word >>> (32 - shift)) >>> 0);
}
/** Is current platform little-endian? Most are. Big-Endian platform: IBM */
const isLE = /* @__PURE__ */ (() => new Uint8Array(new Uint32Array([0x11223344]).buffer)[0] === 0x44)();
/** The byte swap operation for uint32 */
function byteSwap(word) {
    return (((word << 24) & 0xff000000) |
        ((word << 8) & 0xff0000) |
        ((word >>> 8) & 0xff00) |
        ((word >>> 24) & 0xff));
}
/** Conditionally byte swap if on a big-endian platform */
const swap8IfBE = isLE
    ? (n) => n
    : (n) => byteSwap(n);
/** @deprecated */
const byteSwapIfBE = swap8IfBE;
/** In place byte swap for Uint32Array */
function byteSwap32(arr) {
    for (let i = 0; i < arr.length; i++) {
        arr[i] = byteSwap(arr[i]);
    }
    return arr;
}
const swap32IfBE = isLE
    ? (u) => u
    : byteSwap32;
// Built-in hex conversion https://caniuse.com/mdn-javascript_builtins_uint8array_fromhex
const hasHexBuiltin = /* @__PURE__ */ (() => 
// @ts-ignore
typeof Uint8Array.from([]).toHex === 'function' && typeof Uint8Array.fromHex === 'function')();
// Array where index 0xf0 (240) is mapped to string 'f0'
const hexes = /* @__PURE__ */ Array.from({ length: 256 }, (_, i) => i.toString(16).padStart(2, '0'));
/**
 * Convert byte array to hex string. Uses built-in function, when available.
 * @example bytesToHex(Uint8Array.from([0xca, 0xfe, 0x01, 0x23])) // 'cafe0123'
 */
function bytesToHex(bytes) {
    abytes(bytes);
    // @ts-ignore
    if (hasHexBuiltin)
        return bytes.toHex();
    // pre-caching improves the speed 6x
    let hex = '';
    for (let i = 0; i < bytes.length; i++) {
        hex += hexes[bytes[i]];
    }
    return hex;
}
// We use optimized technique to convert hex string to byte array
const asciis = { _0: 48, _9: 57, A: 65, F: 70, a: 97, f: 102 };
function asciiToBase16(ch) {
    if (ch >= asciis._0 && ch <= asciis._9)
        return ch - asciis._0; // '2' => 50-48
    if (ch >= asciis.A && ch <= asciis.F)
        return ch - (asciis.A - 10); // 'B' => 66-(65-10)
    if (ch >= asciis.a && ch <= asciis.f)
        return ch - (asciis.a - 10); // 'b' => 98-(97-10)
    return;
}
/**
 * Convert hex string to byte array. Uses built-in function, when available.
 * @example hexToBytes('cafe0123') // Uint8Array.from([0xca, 0xfe, 0x01, 0x23])
 */
function hexToBytes(hex) {
    if (typeof hex !== 'string')
        throw new Error('hex string expected, got ' + typeof hex);
    // @ts-ignore
    if (hasHexBuiltin)
        return Uint8Array.fromHex(hex);
    const hl = hex.length;
    const al = hl / 2;
    if (hl % 2)
        throw new Error('hex string expected, got unpadded hex of length ' + hl);
    const array = new Uint8Array(al);
    for (let ai = 0, hi = 0; ai < al; ai++, hi += 2) {
        const n1 = asciiToBase16(hex.charCodeAt(hi));
        const n2 = asciiToBase16(hex.charCodeAt(hi + 1));
        if (n1 === undefined || n2 === undefined) {
            const char = hex[hi] + hex[hi + 1];
            throw new Error('hex string expected, got non-hex character "' + char + '" at index ' + hi);
        }
        array[ai] = n1 * 16 + n2; // multiply first octet, e.g. 'a3' => 10*16+3 => 160 + 3 => 163
    }
    return array;
}
/**
 * There is no setImmediate in browser and setTimeout is slow.
 * Call of async fn will return Promise, which will be fullfiled only on
 * next scheduler queue processing step and this is exactly what we need.
 */
const nextTick = async () => { };
/** Returns control to thread each 'tick' ms to avoid blocking. */
async function asyncLoop(iters, tick, cb) {
    let ts = Date.now();
    for (let i = 0; i < iters; i++) {
        cb(i);
        // Date.now() is not monotonic, so in case if clock goes backwards we return return control too
        const diff = Date.now() - ts;
        if (diff >= 0 && diff < tick)
            continue;
        await nextTick();
        ts += diff;
    }
}
/**
 * Converts string to bytes using UTF8 encoding.
 * @example utf8ToBytes('abc') // Uint8Array.from([97, 98, 99])
 */
function utf8ToBytes(str) {
    if (typeof str !== 'string')
        throw new Error('string expected');
    return new Uint8Array(new TextEncoder().encode(str)); // https://bugzil.la/1681809
}
/**
 * Converts bytes to string using UTF8 encoding.
 * @example bytesToUtf8(Uint8Array.from([97, 98, 99])) // 'abc'
 */
function bytesToUtf8(bytes) {
    return new TextDecoder().decode(bytes);
}
/**
 * Normalizes (non-hex) string or Uint8Array to Uint8Array.
 * Warning: when Uint8Array is passed, it would NOT get copied.
 * Keep in mind for future mutable operations.
 */
function toBytes(data) {
    if (typeof data === 'string')
        data = utf8ToBytes(data);
    abytes(data);
    return data;
}
/**
 * Helper for KDFs: consumes uint8array or string.
 * When string is passed, does utf8 decoding, using TextDecoder.
 */
function kdfInputToBytes(data) {
    if (typeof data === 'string')
        data = utf8ToBytes(data);
    abytes(data);
    return data;
}
/** Copies several Uint8Arrays into one. */
function concatBytes(...arrays) {
    let sum = 0;
    for (let i = 0; i < arrays.length; i++) {
        const a = arrays[i];
        abytes(a);
        sum += a.length;
    }
    const res = new Uint8Array(sum);
    for (let i = 0, pad = 0; i < arrays.length; i++) {
        const a = arrays[i];
        res.set(a, pad);
        pad += a.length;
    }
    return res;
}
function checkOpts(defaults, opts) {
    if (opts !== undefined && {}.toString.call(opts) !== '[object Object]')
        throw new Error('options should be object or undefined');
    const merged = Object.assign(defaults, opts);
    return merged;
}
/** For runtime check if class implements interface */
class Hash {
}
/** Wraps hash function, creating an interface on top of it */
function createHasher(hashCons) {
    const hashC = (msg) => hashCons().update(toBytes(msg)).digest();
    const tmp = hashCons();
    hashC.outputLen = tmp.outputLen;
    hashC.blockLen = tmp.blockLen;
    hashC.create = () => hashCons();
    return hashC;
}
function createOptHasher(hashCons) {
    const hashC = (msg, opts) => hashCons(opts).update(toBytes(msg)).digest();
    const tmp = hashCons({});
    hashC.outputLen = tmp.outputLen;
    hashC.blockLen = tmp.blockLen;
    hashC.create = (opts) => hashCons(opts);
    return hashC;
}
function createXOFer(hashCons) {
    const hashC = (msg, opts) => hashCons(opts).update(toBytes(msg)).digest();
    const tmp = hashCons({});
    hashC.outputLen = tmp.outputLen;
    hashC.blockLen = tmp.blockLen;
    hashC.create = (opts) => hashCons(opts);
    return hashC;
}
const wrapConstructor = createHasher;
const wrapConstructorWithOpts = createOptHasher;
const wrapXOFConstructorWithOpts = createXOFer;
/** Cryptographically secure PRNG. Uses internal OS-level `crypto.getRandomValues`. */
function randomBytes(bytesLength = 32) {
    if (_noble_hashes_crypto__WEBPACK_IMPORTED_MODULE_0__.crypto && typeof _noble_hashes_crypto__WEBPACK_IMPORTED_MODULE_0__.crypto.getRandomValues === 'function') {
        return _noble_hashes_crypto__WEBPACK_IMPORTED_MODULE_0__.crypto.getRandomValues(new Uint8Array(bytesLength));
    }
    // Legacy Node.js compatibility
    if (_noble_hashes_crypto__WEBPACK_IMPORTED_MODULE_0__.crypto && typeof _noble_hashes_crypto__WEBPACK_IMPORTED_MODULE_0__.crypto.randomBytes === 'function') {
        return Uint8Array.from(_noble_hashes_crypto__WEBPACK_IMPORTED_MODULE_0__.crypto.randomBytes(bytesLength));
    }
    throw new Error('crypto.getRandomValues must be defined');
}
//# sourceMappingURL=utils.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/api/Authenticator.js":
/*!********************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/api/Authenticator.js ***!
  \********************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   Authenticator: () => (/* binding */ Authenticator)
/* harmony export */ });
/* harmony import */ var _RequestId_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./RequestId.js */ "./node_modules/@unicitylabs/commons/lib/api/RequestId.js");
/* harmony import */ var _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ../cbor/CborDecoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborDecoder.js");
/* harmony import */ var _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ../cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _hash_DataHash_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! ../hash/DataHash.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHash.js");
/* harmony import */ var _signing_Signature_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! ../signing/Signature.js */ "./node_modules/@unicitylabs/commons/lib/signing/Signature.js");
/* harmony import */ var _signing_SigningService_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! ../signing/SigningService.js */ "./node_modules/@unicitylabs/commons/lib/signing/SigningService.js");
/* harmony import */ var _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(/*! ../util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");
/* harmony import */ var _util_StringUtils_js__WEBPACK_IMPORTED_MODULE_7__ = __webpack_require__(/*! ../util/StringUtils.js */ "./node_modules/@unicitylabs/commons/lib/util/StringUtils.js");








/**
 * Represents an Authenticator for signing and verifying transactions.
 */
class Authenticator {
    algorithm;
    _publicKey;
    signature;
    stateHash;
    /**
     * Constructs an Authenticator instance.
     * @param algorithm The signature algorithm used.
     * @param _publicKey The public key as a Uint8Array.
     * @param signature The signature object.
     * @param stateHash The state hash object.
     */
    constructor(algorithm, _publicKey, signature, stateHash) {
        this.algorithm = algorithm;
        this._publicKey = _publicKey;
        this.signature = signature;
        this.stateHash = stateHash;
        this._publicKey = new Uint8Array(_publicKey);
    }
    /**
     * Gets a copy of the public key.
     * @returns The public key as a Uint8Array.
     */
    get publicKey() {
        return new Uint8Array(this._publicKey);
    }
    /**
     * Creates an Authenticator by signing a transaction hash.
     * @param signingService The signing service to use.
     * @param transactionHash The transaction hash to sign.
     * @param stateHash The state hash.
     * @returns A Promise resolving to an Authenticator instance.
     */
    static async create(signingService, transactionHash, stateHash) {
        return new Authenticator(signingService.algorithm, signingService.publicKey, await signingService.sign(transactionHash), stateHash);
    }
    /**
     * Creates an Authenticator from a JSON object.
     * @param data The JSON data.
     * @returns An Authenticator instance.
     * @throws Error if parsing fails.
     */
    static fromJSON(data) {
        if (!Authenticator.isJSON(data)) {
            throw new Error('Parsing authenticator dto failed.');
        }
        return new Authenticator(data.algorithm, _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_6__.HexConverter.decode(data.publicKey), _signing_Signature_js__WEBPACK_IMPORTED_MODULE_4__.Signature.fromJSON(data.signature), _hash_DataHash_js__WEBPACK_IMPORTED_MODULE_3__.DataHash.fromJSON(data.stateHash));
    }
    /**
     * Type guard to check if data is IAuthenticatorJson.
     * @param data The data to check.
     * @returns True if data is IAuthenticatorJson, false otherwise.
     */
    static isJSON(data) {
        return (typeof data === 'object' &&
            data !== null &&
            'publicKey' in data &&
            typeof data.publicKey === 'string' &&
            'algorithm' in data &&
            typeof data.algorithm === 'string' &&
            'signature' in data &&
            typeof data.signature === 'string' &&
            'stateHash' in data &&
            typeof data.stateHash === 'string');
    }
    /**
     * Decodes an Authenticator from CBOR bytes.
     * @param bytes The CBOR-encoded bytes.
     * @returns An Authenticator instance.
     */
    static fromCBOR(bytes) {
        const data = _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readArray(bytes);
        return new Authenticator(_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readTextString(data[0]), _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readByteString(data[1]), _signing_Signature_js__WEBPACK_IMPORTED_MODULE_4__.Signature.decode(_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readByteString(data[2])), _hash_DataHash_js__WEBPACK_IMPORTED_MODULE_3__.DataHash.fromImprint(_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readByteString(data[3])));
    }
    /**
     * Encodes the Authenticator to CBOR format.
     * @returns The CBOR-encoded bytes.
     */
    toCBOR() {
        return _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeArray([
            _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeTextString(this.algorithm),
            _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeByteString(this.publicKey),
            _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeByteString(this.signature.encode()),
            _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeByteString(this.stateHash.imprint),
        ]);
    }
    /**
     * Converts the Authenticator to a JSON object.
     * @returns The Authenticator as IAuthenticatorJson.
     */
    toJSON() {
        return {
            algorithm: this.algorithm,
            publicKey: _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_6__.HexConverter.encode(this.publicKey),
            signature: this.signature.toJSON(),
            stateHash: this.stateHash.toJSON(),
        };
    }
    /**
     * Verifies the signature for a given transaction hash.
     * @param transactionHash The transaction hash to verify.
     * @returns A Promise resolving to true if valid, false otherwise.
     */
    verify(transactionHash) {
        return _signing_SigningService_js__WEBPACK_IMPORTED_MODULE_5__.SigningService.verifyWithPublicKey(transactionHash, this.signature.bytes, this.publicKey);
    }
    /**
     * Calculates the request ID for this Authenticator.
     * @returns A Promise resolving to a RequestId.
     */
    calculateRequestId() {
        return _RequestId_js__WEBPACK_IMPORTED_MODULE_0__.RequestId.create(this._publicKey, this.stateHash);
    }
    /**
     * Returns a string representation of the Authenticator.
     * @returns The string representation.
     */
    toString() {
        return (0,_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_7__.dedent) `
      Authenticator
        Public Key: ${_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_6__.HexConverter.encode(this._publicKey)}
        Signature Algorithm: ${this.algorithm}
        Signature: ${this.signature.toString()}
        State Hash: ${this.stateHash.toString()}`;
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/api/InclusionProof.js":
/*!*********************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/api/InclusionProof.js ***!
  \*********************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   InclusionProof: () => (/* binding */ InclusionProof),
/* harmony export */   InclusionProofVerificationStatus: () => (/* binding */ InclusionProofVerificationStatus)
/* harmony export */ });
/* harmony import */ var _Authenticator_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./Authenticator.js */ "./node_modules/@unicitylabs/commons/lib/api/Authenticator.js");
/* harmony import */ var _LeafValue_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ./LeafValue.js */ "./node_modules/@unicitylabs/commons/lib/api/LeafValue.js");
/* harmony import */ var _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ../cbor/CborDecoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborDecoder.js");
/* harmony import */ var _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! ../cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _hash_DataHash_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! ../hash/DataHash.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHash.js");
/* harmony import */ var _smt_MerkleTreePath_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! ../smt/MerkleTreePath.js */ "./node_modules/@unicitylabs/commons/lib/smt/MerkleTreePath.js");
/* harmony import */ var _util_StringUtils_js__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(/*! ../util/StringUtils.js */ "./node_modules/@unicitylabs/commons/lib/util/StringUtils.js");







/**
 * Status codes for verifying an InclusionProof.
 */
var InclusionProofVerificationStatus;
(function (InclusionProofVerificationStatus) {
    InclusionProofVerificationStatus["NOT_AUTHENTICATED"] = "NOT_AUTHENTICATED";
    InclusionProofVerificationStatus["PATH_NOT_INCLUDED"] = "PATH_NOT_INCLUDED";
    InclusionProofVerificationStatus["PATH_INVALID"] = "PATH_INVALID";
    InclusionProofVerificationStatus["OK"] = "OK";
})(InclusionProofVerificationStatus || (InclusionProofVerificationStatus = {}));
/**
 * Represents a proof of inclusion or non inclusion in a sparse merkle tree.
 */
class InclusionProof {
    merkleTreePath;
    authenticator;
    transactionHash;
    /**
     * Constructs an InclusionProof instance.
     * @param merkleTreePath Sparse merkle tree path.
     * @param authenticator Authenticator.
     * @param transactionHash Transaction hash.
     * @throws Error if authenticator and transactionHash are not both set or both null.
     */
    constructor(merkleTreePath, authenticator, transactionHash) {
        this.merkleTreePath = merkleTreePath;
        this.authenticator = authenticator;
        this.transactionHash = transactionHash;
        if (!this.authenticator != !this.transactionHash) {
            throw new Error('Authenticator and transaction hash must be both set or both null.');
        }
    }
    /**
     * Type guard to check if data is IInclusionProofJson.
     * @param data The data to check.
     * @returns True if data is IInclusionProofJson, false otherwise.
     */
    static isJSON(data) {
        return typeof data === 'object' && data !== null && 'merkleTreePath' in data;
    }
    /**
     * Creates an InclusionProof from a JSON object.
     * @param data The JSON data.
     * @returns An InclusionProof instance.
     * @throws Error if parsing fails.
     */
    static fromJSON(data) {
        if (!InclusionProof.isJSON(data)) {
            throw new Error('Parsing inclusion proof json failed.');
        }
        return new InclusionProof(_smt_MerkleTreePath_js__WEBPACK_IMPORTED_MODULE_5__.MerkleTreePath.fromJSON(data.merkleTreePath), data.authenticator ? _Authenticator_js__WEBPACK_IMPORTED_MODULE_0__.Authenticator.fromJSON(data.authenticator) : null, data.transactionHash ? _hash_DataHash_js__WEBPACK_IMPORTED_MODULE_4__.DataHash.fromJSON(data.transactionHash) : null);
    }
    /**
     * Decodes an InclusionProof from CBOR bytes.
     * @param bytes The CBOR-encoded bytes.
     * @returns An InclusionProof instance.
     */
    static fromCBOR(bytes) {
        const data = _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_2__.CborDecoder.readArray(bytes);
        const authenticator = _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_2__.CborDecoder.readOptional(data[1], _Authenticator_js__WEBPACK_IMPORTED_MODULE_0__.Authenticator.fromCBOR);
        const transactionHash = _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_2__.CborDecoder.readOptional(data[2], _hash_DataHash_js__WEBPACK_IMPORTED_MODULE_4__.DataHash.fromCBOR);
        return new InclusionProof(_smt_MerkleTreePath_js__WEBPACK_IMPORTED_MODULE_5__.MerkleTreePath.fromCBOR(data[0]), authenticator, transactionHash);
    }
    /**
     * Converts the InclusionProof to a JSON object.
     * @returns The InclusionProof as IInclusionProofJson.
     */
    toJSON() {
        return {
            authenticator: this.authenticator?.toJSON() ?? null,
            merkleTreePath: this.merkleTreePath.toJSON(),
            transactionHash: this.transactionHash?.toJSON() ?? null,
        };
    }
    /**
     * Encodes the InclusionProof to CBOR format.
     * @returns The CBOR-encoded bytes.
     */
    toCBOR() {
        return _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_3__.CborEncoder.encodeArray([
            this.merkleTreePath.toCBOR(),
            this.authenticator?.toCBOR() ?? _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_3__.CborEncoder.encodeNull(),
            this.transactionHash?.toCBOR() ?? _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_3__.CborEncoder.encodeNull(),
        ]);
    }
    /**
     * Verifies the inclusion proof for a given request ID.
     * @param requestId The request ID as a bigint.
     * @returns A Promise resolving to the verification status.
     */
    async verify(requestId) {
        if (this.authenticator && this.transactionHash) {
            if (!(await this.authenticator.verify(this.transactionHash))) {
                return InclusionProofVerificationStatus.NOT_AUTHENTICATED;
            }
            const leafValue = await _LeafValue_js__WEBPACK_IMPORTED_MODULE_1__.LeafValue.create(this.authenticator, this.transactionHash);
            if (!leafValue.equals(this.merkleTreePath.steps.at(0)?.branch?.value)) {
                return InclusionProofVerificationStatus.PATH_NOT_INCLUDED;
            }
        }
        const result = await this.merkleTreePath.verify(requestId);
        if (!result.isPathValid) {
            return InclusionProofVerificationStatus.PATH_INVALID;
        }
        if (!result.isPathIncluded) {
            return InclusionProofVerificationStatus.PATH_NOT_INCLUDED;
        }
        return InclusionProofVerificationStatus.OK;
    }
    /**
     * Returns a string representation of the InclusionProof.
     * @returns The string representation.
     */
    toString() {
        return (0,_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_6__.dedent) `
      Inclusion Proof
        ${this.merkleTreePath.toString()}
        ${this.authenticator?.toString()}
        Transaction Hash: ${this.transactionHash?.toString() ?? null}`;
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/api/LeafValue.js":
/*!****************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/api/LeafValue.js ***!
  \****************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   LeafValue: () => (/* binding */ LeafValue)
/* harmony export */ });
/* harmony import */ var _hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ../hash/DataHasher.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHasher.js");
/* harmony import */ var _hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ../hash/HashAlgorithm.js */ "./node_modules/@unicitylabs/commons/lib/hash/HashAlgorithm.js");
/* harmony import */ var _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ../util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");



/**
 * Represents the value of a leaf node in a sparse merkle tree, derived from an authenticator and transaction hash.
 */
class LeafValue {
    _bytes;
    /**
     * Constructs a LeafValue instance.
     * @param _bytes The bytes representing the leaf value.
     */
    constructor(_bytes) {
        this._bytes = _bytes;
        this._bytes = new Uint8Array(_bytes);
    }
    /**
     * Gets a copy of the bytes representing the leaf value.
     * @returns The bytes as a Uint8Array.
     */
    get bytes() {
        return new Uint8Array(this._bytes);
    }
    /**
     * Creates a LeafValue from an authenticator and transaction hash.
     * @param authenticator The authenticator.
     * @param transactionHash The transaction hash.
     * @returns A Promise resolving to a LeafValue instance.
     */
    static async create(authenticator, transactionHash) {
        // TODO: Create cbor object to calculate hash so it would be consistent with everything else?
        const hash = await new _hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_0__.DataHasher(_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_1__.HashAlgorithm.SHA256)
            .update(authenticator.toCBOR())
            .update(transactionHash.imprint)
            .digest();
        return new LeafValue(hash.imprint);
    }
    /**
     * Checks if the given data is equal to this leaf value.
     * @param data The data to compare (ArrayBufferView).
     * @returns True if equal, false otherwise.
     */
    equals(data) {
        if (ArrayBuffer.isView(data)) {
            return (_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_2__.HexConverter.encode(this.bytes) ===
                _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_2__.HexConverter.encode(new Uint8Array(data.buffer, data.byteOffset, data.byteLength)));
        }
        return false;
    }
    /**
     * Returns a string representation of the LeafValue.
     * @returns The string representation.
     */
    toString() {
        return `LeafValue[${_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_2__.HexConverter.encode(this.bytes)}]`;
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/api/RequestId.js":
/*!****************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/api/RequestId.js ***!
  \****************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   RequestId: () => (/* binding */ RequestId)
/* harmony export */ });
/* harmony import */ var _hash_DataHash_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ../hash/DataHash.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHash.js");
/* harmony import */ var _hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ../hash/DataHasher.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHasher.js");
/* harmony import */ var _hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ../hash/HashAlgorithm.js */ "./node_modules/@unicitylabs/commons/lib/hash/HashAlgorithm.js");
/* harmony import */ var _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! ../util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");




/**
 * Represents a unique request identifier derived from a public key and state hash.
 */
class RequestId {
    hash;
    /**
     * Constructs a RequestId instance.
     * @param hash The DataHash representing the request ID.
     */
    constructor(hash) {
        this.hash = hash;
    }
    /**
     * Creates a RequestId from a public key and state hash.
     * @param id The public key as a Uint8Array.
     * @param stateHash The state hash.
     * @returns A Promise resolving to a RequestId instance.
     */
    static create(id, stateHash) {
        return RequestId.createFromImprint(id, stateHash.imprint);
    }
    /**
     * Creates a RequestId from a public key and hash imprint.
     * @param id The public key as a Uint8Array.
     * @param hashImprint The hash imprint as a Uint8Array.
     * @returns A Promise resolving to a RequestId instance.
     */
    static async createFromImprint(id, hashImprint) {
        const hash = await new _hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_1__.DataHasher(_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_2__.HashAlgorithm.SHA256).update(id).update(hashImprint).digest();
        return new RequestId(hash);
    }
    /**
     * Decodes a RequestId from CBOR bytes.
     * @param data The CBOR-encoded bytes.
     * @returns A RequestId instance.
     */
    static fromCBOR(data) {
        return new RequestId(_hash_DataHash_js__WEBPACK_IMPORTED_MODULE_0__.DataHash.fromCBOR(data));
    }
    /**
     * Creates a RequestId from a JSON string.
     * @param data The JSON string.
     * @returns A RequestId instance.
     */
    static fromJSON(data) {
        return new RequestId(_hash_DataHash_js__WEBPACK_IMPORTED_MODULE_0__.DataHash.fromJSON(data));
    }
    /**
     * Converts the RequestId to a bigint.
     * @returns The bigint representation of the request ID.
     */
    toBigInt() {
        return BigInt(`0x01${_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_3__.HexConverter.encode(this.hash.imprint)}`);
    }
    /**
     * Converts the RequestId to a JSON string.
     * @returns The JSON string representation.
     */
    toJSON() {
        return this.hash.toJSON();
    }
    /**
     * Encodes the RequestId to CBOR format.
     * @returns The CBOR-encoded bytes.
     */
    toCBOR() {
        return this.hash.toCBOR();
    }
    /**
     * Checks if this RequestId is equal to another.
     * @param requestId The RequestId to compare.
     * @returns True if equal, false otherwise.
     */
    equals(requestId) {
        return this.hash.equals(requestId.hash);
    }
    /**
     * Returns a string representation of the RequestId.
     * @returns The string representation.
     */
    toString() {
        return `RequestId[${this.hash.toString()}]`;
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/api/SubmitCommitmentRequest.js":
/*!******************************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/api/SubmitCommitmentRequest.js ***!
  \******************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   SubmitCommitmentRequest: () => (/* binding */ SubmitCommitmentRequest)
/* harmony export */ });
/* harmony import */ var _Authenticator_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./Authenticator.js */ "./node_modules/@unicitylabs/commons/lib/api/Authenticator.js");
/* harmony import */ var _RequestId_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ./RequestId.js */ "./node_modules/@unicitylabs/commons/lib/api/RequestId.js");
/* harmony import */ var _hash_DataHash_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ../hash/DataHash.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHash.js");



/**
 * Request object sent by the client to the aggregator.
 */
class SubmitCommitmentRequest {
    requestId;
    transactionHash;
    authenticator;
    receipt;
    /**
     * Constructs a SubmitCommitmentRequest instance.
     * @param requestId The request ID.
     * @param transactionHash The transaction hash.
     * @param authenticator The authenticator.
     * @param receipt Optional flag to request a receipt.
     */
    constructor(requestId, transactionHash, authenticator, receipt) {
        this.requestId = requestId;
        this.transactionHash = transactionHash;
        this.authenticator = authenticator;
        this.receipt = receipt;
    }
    /**
     * Parse a JSON object into a SubmitCommitmentRequest object.
     * @param data Raw request
     * @returns SubmitCommitmentRequest object
     * @throws Error if parsing fails.
     */
    static fromJSON(data) {
        if (!SubmitCommitmentRequest.isJSON(data)) {
            throw new Error('Parsing submit state transition request failed.');
        }
        return new SubmitCommitmentRequest(_RequestId_js__WEBPACK_IMPORTED_MODULE_1__.RequestId.fromJSON(data.requestId), _hash_DataHash_js__WEBPACK_IMPORTED_MODULE_2__.DataHash.fromJSON(data.transactionHash), _Authenticator_js__WEBPACK_IMPORTED_MODULE_0__.Authenticator.fromJSON(data.authenticator), data.receipt);
    }
    /**
     * Check if the given data is a valid JSON request object.
     * @param data Raw request
     * @returns True if the data is a valid JSON request object
     */
    static isJSON(data) {
        return (typeof data === 'object' &&
            data !== null &&
            'authenticator' in data &&
            typeof data.authenticator === 'object' &&
            data.authenticator !== null &&
            'requestId' in data &&
            typeof data.requestId === 'string' &&
            'transactionHash' in data &&
            typeof data.transactionHash === 'string');
    }
    /**
     * Convert the request to a JSON object.
     * @returns JSON object
     */
    toJSON() {
        return {
            authenticator: this.authenticator.toJSON(),
            receipt: this.receipt,
            requestId: this.requestId.toJSON(),
            transactionHash: this.transactionHash.toJSON(),
        };
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/api/SubmitCommitmentResponse.js":
/*!*******************************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/api/SubmitCommitmentResponse.js ***!
  \*******************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   SubmitCommitmentResponse: () => (/* binding */ SubmitCommitmentResponse),
/* harmony export */   SubmitCommitmentStatus: () => (/* binding */ SubmitCommitmentStatus)
/* harmony export */ });
/* harmony import */ var _RequestId_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./RequestId.js */ "./node_modules/@unicitylabs/commons/lib/api/RequestId.js");
/* harmony import */ var _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ../cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _hash_DataHash_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ../hash/DataHash.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHash.js");
/* harmony import */ var _hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! ../hash/DataHasher.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHasher.js");
/* harmony import */ var _hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! ../hash/HashAlgorithm.js */ "./node_modules/@unicitylabs/commons/lib/hash/HashAlgorithm.js");
/* harmony import */ var _signing_Signature_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! ../signing/Signature.js */ "./node_modules/@unicitylabs/commons/lib/signing/Signature.js");
/* harmony import */ var _signing_SigningService_js__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(/*! ../signing/SigningService.js */ "./node_modules/@unicitylabs/commons/lib/signing/SigningService.js");
/* harmony import */ var _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_7__ = __webpack_require__(/*! ../util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");
/* harmony import */ var _util_StringUtils_js__WEBPACK_IMPORTED_MODULE_8__ = __webpack_require__(/*! ../util/StringUtils.js */ "./node_modules/@unicitylabs/commons/lib/util/StringUtils.js");









/**
 * Possible results from the aggregator when submitting a commitment.
 */
var SubmitCommitmentStatus;
(function (SubmitCommitmentStatus) {
    /** The commitment was accepted and stored. */
    SubmitCommitmentStatus["SUCCESS"] = "SUCCESS";
    /** Signature verification failed. */
    SubmitCommitmentStatus["AUTHENTICATOR_VERIFICATION_FAILED"] = "AUTHENTICATOR_VERIFICATION_FAILED";
    /** Request identifier did not match the payload. */
    SubmitCommitmentStatus["REQUEST_ID_MISMATCH"] = "REQUEST_ID_MISMATCH";
    /** A commitment with the same request id already exists. */
    SubmitCommitmentStatus["REQUEST_ID_EXISTS"] = "REQUEST_ID_EXISTS";
})(SubmitCommitmentStatus || (SubmitCommitmentStatus = {}));
/**
 * Request object sent by the client to the aggregator.
 */
class Request {
    service;
    method;
    requestId;
    stateHash;
    transactionHash;
    hash;
    constructor(service, method, requestId, stateHash, transactionHash, hash) {
        this.service = service;
        this.method = method;
        this.requestId = requestId;
        this.stateHash = stateHash;
        this.transactionHash = transactionHash;
        this.hash = hash;
    }
    static async create(service, method, requestId, stateHash, transactionHash) {
        const cborBytes = _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeArray([
            _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeTextString(service),
            _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeTextString(method),
            requestId.toCBOR(),
            stateHash.toCBOR(),
            transactionHash.toCBOR(),
        ]);
        const hash = await new _hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_3__.DataHasher(_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_4__.HashAlgorithm.SHA256).update(cborBytes).digest();
        return new Request(service, method, requestId, stateHash, transactionHash, hash);
    }
    toCBOR() {
        return _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeArray([
            _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeTextString(this.service),
            _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeTextString(this.method),
            this.requestId.toCBOR(),
            this.stateHash.toCBOR(),
            this.transactionHash.toCBOR(),
        ]);
    }
    toJSON() {
        return {
            method: this.method,
            requestId: this.requestId.toJSON(),
            service: this.service,
            stateHash: this.stateHash.toJSON(),
            transactionHash: this.transactionHash.toJSON(),
        };
    }
    toString() {
        return (0,_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_8__.dedent) `
      Request
        Service: ${this.service}
        Method: ${this.method}
        Request ID: ${this.requestId.toString()}
        State Hash: ${this.stateHash.toString()}
        Transaction Hash: ${this.transactionHash.toString()}
      `;
    }
}
/**
 * Response object returned by the aggregator on commitment submission.
 */
class SubmitCommitmentResponse {
    status;
    receipt;
    constructor(status, receipt) {
        this.status = status;
        this.receipt = receipt;
    }
    /**
     * Parse a JSON response object.
     *
     * @param data Raw response
     * @returns Parsed response
     * @throws Error if the data does not match the expected shape
     */
    static async fromJSON(data) {
        if (!SubmitCommitmentResponse.isJSON(data)) {
            throw new Error('Parsing submit state transition response failed.');
        }
        let receipt;
        if (data.request && data.algorithm && data.publicKey && data.signature) {
            const request = await Request.create(data.request.service, data.request.method, _RequestId_js__WEBPACK_IMPORTED_MODULE_0__.RequestId.fromJSON(data.request.requestId), _hash_DataHash_js__WEBPACK_IMPORTED_MODULE_2__.DataHash.fromJSON(data.request.stateHash), _hash_DataHash_js__WEBPACK_IMPORTED_MODULE_2__.DataHash.fromJSON(data.request.transactionHash));
            receipt = {
                algorithm: data.algorithm,
                publicKey: data.publicKey,
                request,
                signature: _signing_Signature_js__WEBPACK_IMPORTED_MODULE_5__.Signature.fromJSON(data.signature),
            };
        }
        return new SubmitCommitmentResponse(data.status, receipt);
    }
    /**
     * Check if the given data is a valid JSON response object.
     *
     * @param data Raw response
     * @returns True if the data is a valid JSON response object
     */
    static isJSON(data) {
        return typeof data === 'object' && data !== null && 'status' in data && typeof data.status === 'string';
    }
    /**
     * Convert the response to a JSON object.
     *
     * @returns JSON representation of the response
     */
    toJSON() {
        return {
            algorithm: this.receipt?.algorithm,
            publicKey: this.receipt?.publicKey,
            request: this.receipt?.request.toJSON(),
            signature: this.receipt?.signature.toJSON(),
            status: this.status,
        };
    }
    async addSignedReceipt(requestId, stateHash, transactionHash, signingService) {
        const request = await Request.create('aggregator', // TODO use actual service identifier
        'submit_commitment', requestId, stateHash, transactionHash);
        const signature = await signingService.sign(request.hash);
        this.receipt = {
            algorithm: signingService.algorithm,
            publicKey: _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_7__.HexConverter.encode(signingService.publicKey),
            request,
            signature,
        };
    }
    /**
     * Verify the receipt of the commitment.
     *
     * @returns True if the receipt is valid, false otherwise
     */
    verifyReceipt() {
        if (!this.receipt) {
            return Promise.resolve(false);
        }
        return _signing_SigningService_js__WEBPACK_IMPORTED_MODULE_6__.SigningService.verifyWithPublicKey(this.receipt.request.hash, this.receipt.signature.bytes, _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_7__.HexConverter.decode(this.receipt.publicKey));
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/cbor/BitMask.js":
/*!***************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/cbor/BitMask.js ***!
  \***************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   BitMask: () => (/* binding */ BitMask)
/* harmony export */ });
var BitMask;
(function (BitMask) {
    BitMask[BitMask["MAJOR_TYPE"] = 224] = "MAJOR_TYPE";
    BitMask[BitMask["ADDITIONAL_INFORMATION"] = 31] = "ADDITIONAL_INFORMATION";
})(BitMask || (BitMask = {}));


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/cbor/CborDecoder.js":
/*!*******************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/cbor/CborDecoder.js ***!
  \*******************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   CborDecoder: () => (/* binding */ CborDecoder)
/* harmony export */ });
/* harmony import */ var _BitMask_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./BitMask.js */ "./node_modules/@unicitylabs/commons/lib/cbor/BitMask.js");
/* harmony import */ var _CborError_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ./CborError.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborError.js");
/* harmony import */ var _MajorType_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ./MajorType.js */ "./node_modules/@unicitylabs/commons/lib/cbor/MajorType.js");
/* harmony import */ var _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! ../util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");




class CborDecoder {
    static readOptional(data, reader) {
        const initialByte = CborDecoder.readByte(data, 0);
        if (initialByte === 0xf6) {
            return null;
        }
        return reader(data);
    }
    static readUnsignedInteger(data) {
        const majorType = CborDecoder.readByte(data, 0) & _BitMask_js__WEBPACK_IMPORTED_MODULE_0__.BitMask.MAJOR_TYPE;
        if (majorType != _MajorType_js__WEBPACK_IMPORTED_MODULE_2__.MajorType.UNSIGNED_INTEGER) {
            throw new _CborError_js__WEBPACK_IMPORTED_MODULE_1__.CborError('Major type mismatch, expected unsigned integer.');
        }
        return CborDecoder.readLength(majorType, data, 0).length;
    }
    static readNegativeInteger() {
        throw new _CborError_js__WEBPACK_IMPORTED_MODULE_1__.CborError('Not implemented.');
    }
    static readByteString(data) {
        const majorType = CborDecoder.readByte(data, 0) & _BitMask_js__WEBPACK_IMPORTED_MODULE_0__.BitMask.MAJOR_TYPE;
        if (majorType != _MajorType_js__WEBPACK_IMPORTED_MODULE_2__.MajorType.BYTE_STRING) {
            throw new _CborError_js__WEBPACK_IMPORTED_MODULE_1__.CborError('Major type mismatch, expected byte string.');
        }
        const { length, position } = CborDecoder.readLength(majorType, data, 0);
        return CborDecoder.read(data, position, Number(length));
    }
    static readTextString(data) {
        const majorType = CborDecoder.readByte(data, 0) & _BitMask_js__WEBPACK_IMPORTED_MODULE_0__.BitMask.MAJOR_TYPE;
        if (majorType != _MajorType_js__WEBPACK_IMPORTED_MODULE_2__.MajorType.TEXT_STRING) {
            throw new _CborError_js__WEBPACK_IMPORTED_MODULE_1__.CborError('Major type mismatch, expected text string.');
        }
        const { length, position } = CborDecoder.readLength(majorType, data, 0);
        return new TextDecoder().decode(CborDecoder.read(data, position, Number(length)));
    }
    static readArray(data) {
        const majorType = CborDecoder.readByte(data, 0) & _BitMask_js__WEBPACK_IMPORTED_MODULE_0__.BitMask.MAJOR_TYPE;
        if (majorType != _MajorType_js__WEBPACK_IMPORTED_MODULE_2__.MajorType.ARRAY) {
            throw new _CborError_js__WEBPACK_IMPORTED_MODULE_1__.CborError('Major type mismatch, expected array.');
        }
        const parsedLength = CborDecoder.readLength(majorType, data, 0);
        let position = parsedLength.position;
        const result = [];
        for (let i = 0; i < parsedLength.length; i++) {
            const rawCbor = CborDecoder.readRawCbor(data, position);
            position = rawCbor.position;
            result.push(rawCbor.data);
        }
        return result;
    }
    static readMap(data) {
        const majorType = CborDecoder.readByte(data, 0) & _BitMask_js__WEBPACK_IMPORTED_MODULE_0__.BitMask.MAJOR_TYPE;
        if (majorType != _MajorType_js__WEBPACK_IMPORTED_MODULE_2__.MajorType.MAP) {
            throw new _CborError_js__WEBPACK_IMPORTED_MODULE_1__.CborError('Major type mismatch, expected map.');
        }
        const parsedLength = CborDecoder.readLength(majorType, data, 0);
        let position = parsedLength.position;
        const result = new Map();
        for (let i = 0; i < parsedLength.length; i++) {
            const key = CborDecoder.readRawCbor(data, position);
            position = key.position;
            const value = CborDecoder.readRawCbor(data, position);
            position = value.position;
            result.set(_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_3__.HexConverter.encode(key.data), value.data);
        }
        return result;
    }
    static readTag(data) {
        const majorType = CborDecoder.readByte(data, 0) & _BitMask_js__WEBPACK_IMPORTED_MODULE_0__.BitMask.MAJOR_TYPE;
        if (majorType != _MajorType_js__WEBPACK_IMPORTED_MODULE_2__.MajorType.TAG) {
            throw new _CborError_js__WEBPACK_IMPORTED_MODULE_1__.CborError('Major type mismatch, expected tag.');
        }
        const { length: tag, position } = CborDecoder.readLength(majorType, data, 0);
        return { data: CborDecoder.readRawCbor(data, position).data, tag };
    }
    static readBoolean(data) {
        const byte = CborDecoder.readByte(data, 0);
        if (byte === 0xf5) {
            return true;
        }
        if (byte === 0xf4) {
            return false;
        }
        throw new _CborError_js__WEBPACK_IMPORTED_MODULE_1__.CborError('Type mismatch, expected boolean.');
    }
    static readLength(majorType, data, offset) {
        const additionalInformation = CborDecoder.readByte(data, offset) & _BitMask_js__WEBPACK_IMPORTED_MODULE_0__.BitMask.ADDITIONAL_INFORMATION;
        if (additionalInformation < 24) {
            return {
                length: BigInt(additionalInformation),
                position: offset + 1,
            };
        }
        switch (majorType) {
            case _MajorType_js__WEBPACK_IMPORTED_MODULE_2__.MajorType.ARRAY:
            case _MajorType_js__WEBPACK_IMPORTED_MODULE_2__.MajorType.BYTE_STRING:
            case _MajorType_js__WEBPACK_IMPORTED_MODULE_2__.MajorType.TEXT_STRING:
                if (additionalInformation == 31) {
                    throw new _CborError_js__WEBPACK_IMPORTED_MODULE_1__.CborError('Indefinite length array not supported.');
                }
        }
        if (additionalInformation > 27) {
            throw new _CborError_js__WEBPACK_IMPORTED_MODULE_1__.CborError('Encoded item is not well-formed.');
        }
        const numberOfLengthBytes = Math.pow(2, additionalInformation - 24);
        let t = BigInt(0);
        for (let i = 0; i < numberOfLengthBytes; ++i) {
            t = (t << 8n) | BigInt(CborDecoder.readByte(data, offset + 1 + i));
        }
        return {
            length: t,
            position: offset + numberOfLengthBytes + 1,
        };
    }
    static readRawCbor(data, offset) {
        const majorType = CborDecoder.readByte(data, offset) & _BitMask_js__WEBPACK_IMPORTED_MODULE_0__.BitMask.MAJOR_TYPE;
        const parsedLength = CborDecoder.readLength(majorType, data, offset);
        const length = parsedLength.length;
        let position = parsedLength.position;
        switch (majorType) {
            case _MajorType_js__WEBPACK_IMPORTED_MODULE_2__.MajorType.BYTE_STRING:
            case _MajorType_js__WEBPACK_IMPORTED_MODULE_2__.MajorType.TEXT_STRING:
                position += Number(length);
                break;
            case _MajorType_js__WEBPACK_IMPORTED_MODULE_2__.MajorType.ARRAY:
                for (let i = 0; i < length; i++) {
                    position = CborDecoder.readRawCbor(data, position).position;
                }
                break;
            case _MajorType_js__WEBPACK_IMPORTED_MODULE_2__.MajorType.MAP:
                for (let i = 0; i < length; i++) {
                    position = CborDecoder.readRawCbor(data, position).position;
                    position = CborDecoder.readRawCbor(data, position).position;
                }
                break;
            case _MajorType_js__WEBPACK_IMPORTED_MODULE_2__.MajorType.TAG:
                position = CborDecoder.readRawCbor(data, position).position;
                break;
        }
        return {
            data: CborDecoder.read(data, offset, position - offset),
            position,
        };
    }
    static readByte(data, offset) {
        if (data.length < offset) {
            throw new _CborError_js__WEBPACK_IMPORTED_MODULE_1__.CborError('Premature end of data.');
        }
        return data[offset] & 0xff;
    }
    static read(data, offset, length) {
        if (data.length < offset + length) {
            throw new _CborError_js__WEBPACK_IMPORTED_MODULE_1__.CborError('Premature end of data.');
        }
        return data.subarray(offset, offset + length);
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js":
/*!*******************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js ***!
  \*******************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   CborEncoder: () => (/* binding */ CborEncoder)
/* harmony export */ });
/* harmony import */ var _CborError_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./CborError.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborError.js");
/* harmony import */ var _MajorType_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ./MajorType.js */ "./node_modules/@unicitylabs/commons/lib/cbor/MajorType.js");
/* harmony import */ var _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ../util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");



class CborEncoder {
    static encodeOptional(data, encoder) {
        if (data == null) {
            return new Uint8Array([0xf6]);
        }
        return encoder(data);
    }
    static encodeUnsignedInteger(input) {
        if (input < 0) {
            throw new _CborError_js__WEBPACK_IMPORTED_MODULE_0__.CborError('Only unsigned numbers are allowed.');
        }
        if (input < 24) {
            return new Uint8Array([_MajorType_js__WEBPACK_IMPORTED_MODULE_1__.MajorType.UNSIGNED_INTEGER | Number(input)]);
        }
        const bytes = CborEncoder.getUnsignedIntegerAsPaddedBytes(input);
        return new Uint8Array([
            _MajorType_js__WEBPACK_IMPORTED_MODULE_1__.MajorType.UNSIGNED_INTEGER | CborEncoder.getAdditionalInformationBits(bytes.length),
            ...bytes,
        ]);
    }
    static encodeByteString(input) {
        if (input.length < 24) {
            return new Uint8Array([_MajorType_js__WEBPACK_IMPORTED_MODULE_1__.MajorType.BYTE_STRING | input.length, ...input]);
        }
        const lengthBytes = CborEncoder.getUnsignedIntegerAsPaddedBytes(input.length);
        return new Uint8Array([
            _MajorType_js__WEBPACK_IMPORTED_MODULE_1__.MajorType.BYTE_STRING | CborEncoder.getAdditionalInformationBits(lengthBytes.length),
            ...lengthBytes,
            ...input,
        ]);
    }
    static encodeTextString(input) {
        const bytes = new TextEncoder().encode(input);
        if (bytes.length < 24) {
            return new Uint8Array([_MajorType_js__WEBPACK_IMPORTED_MODULE_1__.MajorType.TEXT_STRING | bytes.length, ...bytes]);
        }
        const lengthBytes = CborEncoder.getUnsignedIntegerAsPaddedBytes(bytes.length);
        return new Uint8Array([
            _MajorType_js__WEBPACK_IMPORTED_MODULE_1__.MajorType.TEXT_STRING | CborEncoder.getAdditionalInformationBits(lengthBytes.length),
            ...lengthBytes,
            ...bytes,
        ]);
    }
    static encodeArray(input) {
        const data = new Uint8Array(input.reduce((result, value) => result + value.length, 0));
        let length = 0;
        for (const value of input) {
            data.set(value, length);
            length += value.length;
        }
        if (input.length < 24) {
            return new Uint8Array([_MajorType_js__WEBPACK_IMPORTED_MODULE_1__.MajorType.ARRAY | input.length, ...data]);
        }
        const lengthBytes = CborEncoder.getUnsignedIntegerAsPaddedBytes(input.length);
        return new Uint8Array([
            _MajorType_js__WEBPACK_IMPORTED_MODULE_1__.MajorType.ARRAY | CborEncoder.getAdditionalInformationBits(lengthBytes.length),
            ...lengthBytes,
            ...data,
        ]);
    }
    static encodeMap(input) {
        const processedArray = Array.from(input.entries()).map(([key, value]) => [_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_2__.HexConverter.decode(key), value]);
        processedArray.sort(([a], [b]) => {
            if (a.length !== b.length) {
                return a.length - b.length;
            }
            for (let i = 0; i < a.length; i++) {
                if (a[i] !== b[i]) {
                    return a[i] - b[i];
                }
            }
            return 0;
        });
        const dataLength = processedArray.reduce((result, [key, value]) => result + key.length + value.length, 0);
        const data = new Uint8Array(dataLength);
        let length = 0;
        for (const [key, value] of processedArray) {
            data.set(key, length);
            length += key.length;
            data.set(value, length);
            length += value.length;
        }
        if (input.size < 24) {
            return new Uint8Array([_MajorType_js__WEBPACK_IMPORTED_MODULE_1__.MajorType.MAP | input.size, ...data]);
        }
        const lengthBytes = CborEncoder.getUnsignedIntegerAsPaddedBytes(input.size);
        return new Uint8Array([
            _MajorType_js__WEBPACK_IMPORTED_MODULE_1__.MajorType.MAP | CborEncoder.getAdditionalInformationBits(lengthBytes.length),
            ...lengthBytes,
            ...data,
        ]);
    }
    static encodeTag(tag, input) {
        if (tag < 24) {
            return new Uint8Array([_MajorType_js__WEBPACK_IMPORTED_MODULE_1__.MajorType.TAG | Number(tag), ...input]);
        }
        const bytes = CborEncoder.getUnsignedIntegerAsPaddedBytes(tag);
        return new Uint8Array([_MajorType_js__WEBPACK_IMPORTED_MODULE_1__.MajorType.TAG | CborEncoder.getAdditionalInformationBits(bytes.length), ...bytes, ...input]);
    }
    static encodeBoolean(data) {
        if (data) {
            return new Uint8Array([0xf5]);
        }
        return new Uint8Array([0xf4]);
    }
    static encodeNull() {
        return new Uint8Array([0xf6]);
    }
    static getAdditionalInformationBits(length) {
        return 24 + Math.ceil(Math.log2(length));
    }
    static getUnsignedIntegerAsPaddedBytes(input) {
        if (input < 0) {
            throw new _CborError_js__WEBPACK_IMPORTED_MODULE_0__.CborError('Only unsigned numbers are allowed.');
        }
        let t;
        const bytes = [];
        for (t = BigInt(input); t > 0; t = t >> 8n) {
            bytes.push(Number(t & 255n));
        }
        if (bytes.length > 8) {
            throw new _CborError_js__WEBPACK_IMPORTED_MODULE_0__.CborError('Number is not unsigned long.');
        }
        if (bytes.length === 0) {
            bytes.push(0);
        }
        bytes.reverse();
        const data = new Uint8Array(Math.pow(2, Math.ceil(Math.log2(bytes.length))));
        data.set(bytes, data.length - bytes.length);
        return data;
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/cbor/CborError.js":
/*!*****************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/cbor/CborError.js ***!
  \*****************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   CborError: () => (/* binding */ CborError)
/* harmony export */ });
class CborError extends Error {
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/cbor/MajorType.js":
/*!*****************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/cbor/MajorType.js ***!
  \*****************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   MajorType: () => (/* binding */ MajorType)
/* harmony export */ });
var MajorType;
(function (MajorType) {
    MajorType[MajorType["UNSIGNED_INTEGER"] = 0] = "UNSIGNED_INTEGER";
    MajorType[MajorType["NEGATIVE_INTEGER"] = 32] = "NEGATIVE_INTEGER";
    MajorType[MajorType["BYTE_STRING"] = 64] = "BYTE_STRING";
    MajorType[MajorType["TEXT_STRING"] = 96] = "TEXT_STRING";
    MajorType[MajorType["ARRAY"] = 128] = "ARRAY";
    MajorType[MajorType["MAP"] = 160] = "MAP";
    MajorType[MajorType["TAG"] = 192] = "TAG";
    MajorType[MajorType["FLOAT_SIMPLE_BREAK"] = 224] = "FLOAT_SIMPLE_BREAK";
})(MajorType || (MajorType = {}));


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/hash/DataHash.js":
/*!****************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/hash/DataHash.js ***!
  \****************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   DataHash: () => (/* binding */ DataHash)
/* harmony export */ });
/* harmony import */ var _HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./HashAlgorithm.js */ "./node_modules/@unicitylabs/commons/lib/hash/HashAlgorithm.js");
/* harmony import */ var _HashError_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ./HashError.js */ "./node_modules/@unicitylabs/commons/lib/hash/HashError.js");
/* harmony import */ var _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ../cbor/CborDecoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborDecoder.js");
/* harmony import */ var _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! ../cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! ../util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");





class DataHash {
    algorithm;
    _data;
    _imprint;
    constructor(algorithm, _data) {
        this.algorithm = algorithm;
        this._data = _data;
        this._data = new Uint8Array(_data);
        this._imprint = new Uint8Array(_data.length + 2);
        this._imprint.set([(algorithm & 0xff00) >> 8, algorithm & 0xff]);
        this._imprint.set(new Uint8Array(_data), 2);
    }
    get data() {
        return new Uint8Array(this._data);
    }
    /**
     * Returns the imprint of the hash, which includes the algorithm identifier and the data.
     * The first two bytes represent the algorithm, followed by the data bytes.
     * NB! Do not use this for signing, use `data` instead.
     */
    get imprint() {
        return new Uint8Array(this._imprint);
    }
    static fromImprint(imprint) {
        if (imprint.length < 3) {
            throw new _HashError_js__WEBPACK_IMPORTED_MODULE_1__.HashError('Imprint must have 2 bytes of algorithm and at least 1 byte of data.');
        }
        const algorithm = (imprint[0] << 8) | imprint[1];
        return new DataHash(algorithm, imprint.subarray(2));
    }
    static fromJSON(data) {
        return DataHash.fromImprint(_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.decode(data));
    }
    static fromCBOR(bytes) {
        return DataHash.fromImprint(_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_2__.CborDecoder.readByteString(bytes));
    }
    toJSON() {
        return _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(this._imprint);
    }
    toCBOR() {
        return _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_3__.CborEncoder.encodeByteString(this._imprint);
    }
    equals(hash) {
        return _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(this._imprint) === _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(hash._imprint);
    }
    toString() {
        return `[${_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_0__.HashAlgorithm[this.algorithm]}]${_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(this._data)}`;
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/hash/DataHasher.js":
/*!******************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/hash/DataHasher.js ***!
  \******************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   Algorithm: () => (/* binding */ Algorithm),
/* harmony export */   DataHasher: () => (/* binding */ DataHasher)
/* harmony export */ });
/* harmony import */ var _noble_hashes_ripemd160__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! @noble/hashes/ripemd160 */ "./node_modules/@noble/hashes/esm/ripemd160.js");
/* harmony import */ var _noble_hashes_sha256__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! @noble/hashes/sha256 */ "./node_modules/@noble/hashes/esm/sha256.js");
/* harmony import */ var _noble_hashes_sha512__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! @noble/hashes/sha512 */ "./node_modules/@noble/hashes/esm/sha512.js");
/* harmony import */ var _DataHash_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./DataHash.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHash.js");
/* harmony import */ var _HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ./HashAlgorithm.js */ "./node_modules/@unicitylabs/commons/lib/hash/HashAlgorithm.js");
/* harmony import */ var _UnsupportedHashAlgorithmError_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ./UnsupportedHashAlgorithmError.js */ "./node_modules/@unicitylabs/commons/lib/hash/UnsupportedHashAlgorithmError.js");






const Algorithm = {
    [_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_1__.HashAlgorithm.RIPEMD160]: _noble_hashes_ripemd160__WEBPACK_IMPORTED_MODULE_3__.ripemd160,
    [_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_1__.HashAlgorithm.SHA224]: _noble_hashes_sha256__WEBPACK_IMPORTED_MODULE_4__.sha224,
    [_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_1__.HashAlgorithm.SHA256]: _noble_hashes_sha256__WEBPACK_IMPORTED_MODULE_4__.sha256,
    [_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_1__.HashAlgorithm.SHA384]: _noble_hashes_sha512__WEBPACK_IMPORTED_MODULE_5__.sha384,
    [_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_1__.HashAlgorithm.SHA512]: _noble_hashes_sha512__WEBPACK_IMPORTED_MODULE_5__.sha512,
};
/**
 * Provides synchronous hashing functions
 */
class DataHasher {
    algorithm;
    _messageDigest;
    /**
     * Create DataHasher instance the hash algorithm
     * @param {HashAlgorithm} algorithm
     */
    constructor(algorithm) {
        this.algorithm = algorithm;
        if (!Algorithm[algorithm]) {
            throw new _UnsupportedHashAlgorithmError_js__WEBPACK_IMPORTED_MODULE_2__.UnsupportedHashAlgorithmError(algorithm);
        }
        this._messageDigest = Algorithm[algorithm].create();
    }
    /**
     * Add data for hashing
     * @param {Uint8Array} data byte array
     * @returns {DataHasher}
     */
    update(data) {
        this._messageDigest.update(data);
        return this;
    }
    /**
     * Hashes the data and returns the DataHash
     * @returns DataHash
     */
    digest() {
        return Promise.resolve(new _DataHash_js__WEBPACK_IMPORTED_MODULE_0__.DataHash(this.algorithm, this._messageDigest.digest()));
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/hash/HashAlgorithm.js":
/*!*********************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/hash/HashAlgorithm.js ***!
  \*********************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   HashAlgorithm: () => (/* binding */ HashAlgorithm)
/* harmony export */ });
var HashAlgorithm;
(function (HashAlgorithm) {
    HashAlgorithm[HashAlgorithm["SHA256"] = 0] = "SHA256";
    HashAlgorithm[HashAlgorithm["SHA224"] = 1] = "SHA224";
    HashAlgorithm[HashAlgorithm["SHA384"] = 2] = "SHA384";
    HashAlgorithm[HashAlgorithm["SHA512"] = 3] = "SHA512";
    HashAlgorithm[HashAlgorithm["RIPEMD160"] = 4] = "RIPEMD160";
})(HashAlgorithm || (HashAlgorithm = {}));


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/hash/HashError.js":
/*!*****************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/hash/HashError.js ***!
  \*****************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   HashError: () => (/* binding */ HashError)
/* harmony export */ });
/**
 * Hashing error
 */
class HashError extends Error {
    constructor(message) {
        super(message);
        this.name = 'HashError';
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/hash/UnsupportedHashAlgorithmError.js":
/*!*************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/hash/UnsupportedHashAlgorithmError.js ***!
  \*************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   UnsupportedHashAlgorithmError: () => (/* binding */ UnsupportedHashAlgorithmError)
/* harmony export */ });
class UnsupportedHashAlgorithmError extends Error {
    constructor(algorithm) {
        super(`Unsupported hash algorithm: ${algorithm}`);
        this.name = 'UnsupportedHashAlgorithm';
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/json-rpc/JsonRpcDataError.js":
/*!****************************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/json-rpc/JsonRpcDataError.js ***!
  \****************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   JsonRpcDataError: () => (/* binding */ JsonRpcDataError)
/* harmony export */ });
/**
 * JSON-RPC error object.
 */
class JsonRpcDataError {
    code;
    message;
    name = 'JsonRpcError';
    /**
     * JSON-RPC error object constructor.
     * @param {{code: number; message: string}} data Error data.
     */
    constructor({ code, message }) {
        this.code = code;
        this.message = message;
    }
    /**
     * Error info to string.
     */
    toString() {
        return `{ code: ${this.code}, message: ${this.message} }`;
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/json-rpc/JsonRpcHttpTransport.js":
/*!********************************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/json-rpc/JsonRpcHttpTransport.js ***!
  \********************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   JsonRpcHttpTransport: () => (/* binding */ JsonRpcHttpTransport)
/* harmony export */ });
/* harmony import */ var uuid__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! uuid */ "./node_modules/uuid/dist/esm-browser/v4.js");
/* harmony import */ var _JsonRpcDataError_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./JsonRpcDataError.js */ "./node_modules/@unicitylabs/commons/lib/json-rpc/JsonRpcDataError.js");
/* harmony import */ var _JsonRpcNetworkError_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ./JsonRpcNetworkError.js */ "./node_modules/@unicitylabs/commons/lib/json-rpc/JsonRpcNetworkError.js");



/**
 * JSON-RPC HTTP service.
 */
class JsonRpcHttpTransport {
    url;
    /**
     * JSON-RPC HTTP service constructor.
     */
    constructor(url) {
        this.url = url;
    }
    /**
     * Send a JSON-RPC request.
     */
    async request(method, params) {
        const response = await fetch(this.url, {
            body: JSON.stringify({
                id: (0,uuid__WEBPACK_IMPORTED_MODULE_2__["default"])(),
                jsonrpc: '2.0',
                method,
                params,
            }),
            headers: { 'Content-Type': 'application/json' },
            method: 'POST',
        });
        if (!response.ok) {
            throw new _JsonRpcNetworkError_js__WEBPACK_IMPORTED_MODULE_1__.JsonRpcNetworkError(response.status, await response.text());
        }
        const data = (await response.json());
        if (data.error) {
            throw new _JsonRpcDataError_js__WEBPACK_IMPORTED_MODULE_0__.JsonRpcDataError(data.error);
        }
        return data.result;
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/json-rpc/JsonRpcNetworkError.js":
/*!*******************************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/json-rpc/JsonRpcNetworkError.js ***!
  \*******************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   JsonRpcNetworkError: () => (/* binding */ JsonRpcNetworkError)
/* harmony export */ });
/**
 * JSON-RPC error object.
 */
class JsonRpcNetworkError {
    status;
    message;
    name = 'JsonRpcNetworkError';
    constructor(status, message) {
        this.status = status;
        this.message = message;
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/signing/Signature.js":
/*!********************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/signing/Signature.js ***!
  \********************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   Signature: () => (/* binding */ Signature)
/* harmony export */ });
/* harmony import */ var _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ../cbor/CborDecoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborDecoder.js");
/* harmony import */ var _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ../cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ../util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");



class Signature {
    _bytes;
    recovery;
    algorithm = 'secp256k1';
    constructor(_bytes, recovery) {
        this._bytes = _bytes;
        this.recovery = recovery;
        this._bytes = new Uint8Array(_bytes);
    }
    get bytes() {
        return new Uint8Array(this._bytes);
    }
    static fromCBOR(bytes) {
        return Signature.decode(_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__.CborDecoder.readByteString(bytes));
    }
    static decode(bytes) {
        if (bytes.length !== 65) {
            throw new Error('Signature must contain signature and recovery byte.');
        }
        return new Signature(bytes.slice(0, -1), bytes[bytes.length - 1]);
    }
    static fromJSON(data) {
        return Signature.decode(_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_2__.HexConverter.decode(data));
    }
    toJSON() {
        return _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_2__.HexConverter.encode(this.encode());
    }
    toCBOR() {
        return _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeByteString(this.encode());
    }
    encode() {
        return new Uint8Array([...this._bytes, this.recovery]);
    }
    toString() {
        return `${_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_2__.HexConverter.encode(this.encode())}`;
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/signing/SigningService.js":
/*!*************************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/signing/SigningService.js ***!
  \*************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   SigningService: () => (/* binding */ SigningService)
/* harmony export */ });
/* harmony import */ var _noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! @noble/curves/secp256k1 */ "./node_modules/@noble/curves/esm/secp256k1.js");
/* harmony import */ var _Signature_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./Signature.js */ "./node_modules/@unicitylabs/commons/lib/signing/Signature.js");
/* harmony import */ var _hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ../hash/DataHasher.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHasher.js");
/* harmony import */ var _hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ../hash/HashAlgorithm.js */ "./node_modules/@unicitylabs/commons/lib/hash/HashAlgorithm.js");




/**
 * Default signing service.
 * @implements {ISigningService}
 */
class SigningService {
    privateKey;
    _publicKey;
    /**
     * Signing service constructor.
     * @param {Uint8Array} privateKey private key bytes.
     */
    constructor(privateKey) {
        this.privateKey = privateKey;
        this.privateKey = new Uint8Array(privateKey);
        this._publicKey = _noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_3__.secp256k1.getPublicKey(this.privateKey, true);
    }
    /**
     * @see {ISigningService.publicKey}
     */
    get publicKey() {
        return new Uint8Array(this._publicKey);
    }
    get algorithm() {
        return 'secp256k1';
    }
    static generatePrivateKey() {
        return _noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_3__.secp256k1.utils.randomPrivateKey();
    }
    static async createFromSecret(secret, nonce) {
        const hasher = new _hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_1__.DataHasher(_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_2__.HashAlgorithm.SHA256);
        hasher.update(secret);
        if (nonce) {
            hasher.update(nonce);
        }
        const hash = await hasher.digest();
        return new SigningService(hash.data);
    }
    static verifySignatureWithRecoveredPublicKey(hash, signature) {
        const publicKey = _noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_3__.secp256k1.Signature.fromCompact(signature.bytes)
            .addRecoveryBit(signature.recovery)
            .recoverPublicKey(hash.data)
            .toRawBytes();
        return SigningService.verifyWithPublicKey(hash, signature.bytes, publicKey);
    }
    /**
     * Verify secp256k1 signature hash.
     * @param {Uint8Array} hash Hash.
     * @param {Uint8Array} signature Signature.
     * @param {Uint8Array} publicKey Public key.
     */
    static verifyWithPublicKey(hash, signature, publicKey) {
        return Promise.resolve(_noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_3__.secp256k1.verify(signature, hash.data, publicKey, { format: 'compact' }));
    }
    /**
     * Verify secp256k1 signature hash.
     * @param {Uint8Array} hash Hash.
     * @param {Uint8Array} signature Signature.
     */
    verify(hash, signature) {
        return SigningService.verifyWithPublicKey(hash, signature.bytes, this._publicKey);
    }
    /**
     * @see {ISigningService.sign} 32-byte hash.
     */
    sign(hash) {
        const signature = _noble_curves_secp256k1__WEBPACK_IMPORTED_MODULE_3__.secp256k1.sign(hash.data, this.privateKey);
        return Promise.resolve(new _Signature_js__WEBPACK_IMPORTED_MODULE_0__.Signature(signature.toCompactRawBytes(), signature.recovery));
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/smst/LeafBranch.js":
/*!******************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/smst/LeafBranch.js ***!
  \******************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   LeafBranch: () => (/* binding */ LeafBranch)
/* harmony export */ });
/* harmony import */ var _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ../util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");
/* harmony import */ var _util_StringUtils_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ../util/StringUtils.js */ "./node_modules/@unicitylabs/commons/lib/util/StringUtils.js");


class LeafBranch {
    path;
    _value;
    sum;
    hash;
    constructor(path, _value, sum, hash) {
        this.path = path;
        this._value = _value;
        this.sum = sum;
        this.hash = hash;
    }
    get value() {
        return new Uint8Array(this._value);
    }
    finalize() {
        return Promise.resolve(this);
    }
    toString() {
        return (0,_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_1__.dedent) `
      Leaf[${this.path.toString(2)}]
        Hash: ${this.hash.toString()}
        Value: ${_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_0__.HexConverter.encode(this._value)}
        Sum: ${this.sum}`;
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/smst/MerkleSumTreePath.js":
/*!*************************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/smst/MerkleSumTreePath.js ***!
  \*************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   MerkleSumTreePath: () => (/* binding */ MerkleSumTreePath)
/* harmony export */ });
/* harmony import */ var _MerkleSumTreePathStep_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./MerkleSumTreePathStep.js */ "./node_modules/@unicitylabs/commons/lib/smst/MerkleSumTreePathStep.js");
/* harmony import */ var _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ../cbor/CborDecoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborDecoder.js");
/* harmony import */ var _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ../cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _hash_DataHash_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! ../hash/DataHash.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHash.js");
/* harmony import */ var _hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! ../hash/DataHasher.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHasher.js");
/* harmony import */ var _hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! ../hash/HashAlgorithm.js */ "./node_modules/@unicitylabs/commons/lib/hash/HashAlgorithm.js");
/* harmony import */ var _smt_PathVerificationResult_js__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(/*! ../smt/PathVerificationResult.js */ "./node_modules/@unicitylabs/commons/lib/smt/PathVerificationResult.js");
/* harmony import */ var _util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_7__ = __webpack_require__(/*! ../util/BigintConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/BigintConverter.js");
/* harmony import */ var _util_StringUtils_js__WEBPACK_IMPORTED_MODULE_8__ = __webpack_require__(/*! ../util/StringUtils.js */ "./node_modules/@unicitylabs/commons/lib/util/StringUtils.js");









class MerkleSumTreePath {
    root;
    sum;
    steps;
    constructor(root, sum, steps) {
        this.root = root;
        this.sum = sum;
        this.steps = steps;
    }
    static fromJSON(data) {
        if (!MerkleSumTreePath.isJSON(data)) {
            throw new Error('Parsing merkle tree path json failed.');
        }
        return new MerkleSumTreePath(_hash_DataHash_js__WEBPACK_IMPORTED_MODULE_3__.DataHash.fromJSON(data.root), BigInt(data.sum), data.steps.map((step) => _MerkleSumTreePathStep_js__WEBPACK_IMPORTED_MODULE_0__.MerkleSumTreePathStep.fromJSON(step)));
    }
    static isJSON(data) {
        return (typeof data === 'object' &&
            data !== null &&
            'root' in data &&
            typeof data.root === 'string' &&
            'steps' in data &&
            Array.isArray(data.steps));
    }
    static fromCBOR(bytes) {
        const data = _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readArray(bytes);
        return new MerkleSumTreePath(_hash_DataHash_js__WEBPACK_IMPORTED_MODULE_3__.DataHash.fromCBOR(data[0]), _util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_7__.BigintConverter.decode(_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readByteString(data[1])), _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readArray(data[2]).map((step) => _MerkleSumTreePathStep_js__WEBPACK_IMPORTED_MODULE_0__.MerkleSumTreePathStep.fromCBOR(step)));
    }
    toCBOR() {
        return _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeArray([
            this.root.toCBOR(),
            _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeArray(this.steps.map((step) => step.toCBOR())),
        ]);
    }
    toJSON() {
        return {
            root: this.root.toJSON(),
            steps: this.steps.map((step) => step.toJSON()),
            sum: this.sum.toString(),
        };
    }
    async verify(requestId) {
        let currentPath = 1n;
        let currentHash = null;
        let currentSum = this.steps.at(0)?.branch?.sum ?? 0n;
        for (let i = 0; i < this.steps.length; i++) {
            const step = this.steps[i];
            let hash = null;
            if (step.branch !== null) {
                const bytes = i === 0 ? step.branch.value : currentHash ? currentHash.imprint : null;
                hash = await new _hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_4__.DataHasher(_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_5__.HashAlgorithm.SHA256)
                    .update(_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeArray([
                    _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeByteString(_util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_7__.BigintConverter.encode(step.path)),
                    bytes ? _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeByteString(bytes) : _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeNull(),
                    _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeByteString(_util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_7__.BigintConverter.encode(currentSum)),
                ]))
                    .digest();
                const length = BigInt(step.path.toString(2).length - 1);
                currentPath = (currentPath << length) | (step.path & ((1n << length) - 1n));
            }
            const isRight = step.path & 1n;
            const right = isRight
                ? hash
                    ? [hash, currentSum]
                    : null
                : step.sibling
                    ? [step.sibling.hash, step.sibling.sum]
                    : null;
            const left = isRight
                ? step.sibling
                    ? [step.sibling.hash, step.sibling.sum]
                    : null
                : hash
                    ? [hash, currentSum]
                    : null;
            currentHash = await new _hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_4__.DataHasher(_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_5__.HashAlgorithm.SHA256)
                .update(_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeArray([
                left
                    ? _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeArray([
                        _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeByteString(left[0].imprint),
                        _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeByteString(_util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_7__.BigintConverter.encode(left[1])),
                    ])
                    : _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeNull(),
                right
                    ? _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeArray([
                        right[0] ? _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeByteString(right[0].imprint) : _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeNull(),
                        _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeByteString(_util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_7__.BigintConverter.encode(right[1])),
                    ])
                    : _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeNull(),
            ]))
                .digest();
            currentSum += step.sibling?.sum ?? 0n;
        }
        return new _smt_PathVerificationResult_js__WEBPACK_IMPORTED_MODULE_6__.PathVerificationResult(!!currentHash && this.root.equals(currentHash) && currentSum === this.sum, requestId === currentPath);
    }
    toString() {
        return (0,_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_8__.dedent) `
      Merkle Tree Path
        Root: ${this.root.toString()} 
        Steps: [
          ${this.steps.map((step) => step?.toString() ?? 'null').join('\n')}
        ]`;
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/smst/MerkleSumTreePathStep.js":
/*!*****************************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/smst/MerkleSumTreePathStep.js ***!
  \*****************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   MerkleSumTreePathStep: () => (/* binding */ MerkleSumTreePathStep)
/* harmony export */ });
/* harmony import */ var _LeafBranch_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./LeafBranch.js */ "./node_modules/@unicitylabs/commons/lib/smst/LeafBranch.js");
/* harmony import */ var _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ../cbor/CborDecoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborDecoder.js");
/* harmony import */ var _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ../cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _hash_DataHash_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! ../hash/DataHash.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHash.js");
/* harmony import */ var _util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! ../util/BigintConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/BigintConverter.js");
/* harmony import */ var _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! ../util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");
/* harmony import */ var _util_StringUtils_js__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(/*! ../util/StringUtils.js */ "./node_modules/@unicitylabs/commons/lib/util/StringUtils.js");







class MerkleSumTreePathStepSibling {
    sum;
    hash;
    constructor(sum, hash) {
        this.sum = sum;
        this.hash = hash;
    }
    static create(sibling) {
        return new MerkleSumTreePathStepSibling(sibling.sum, sibling.hash);
    }
    static isJSON(data) {
        return Array.isArray(data);
    }
    static fromJSON(data) {
        if (!Array.isArray(data) || data.length !== 2) {
            throw new Error('Parsing merkle tree path step branch failed.');
        }
        return new MerkleSumTreePathStepSibling(BigInt(data[0]), _hash_DataHash_js__WEBPACK_IMPORTED_MODULE_3__.DataHash.fromJSON(data[1]));
    }
    static fromCBOR(bytes) {
        const data = _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readArray(bytes);
        return new MerkleSumTreePathStepSibling(_util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_4__.BigintConverter.decode(_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readByteString(data[0])), _hash_DataHash_js__WEBPACK_IMPORTED_MODULE_3__.DataHash.fromCBOR(data[1]));
    }
    toCBOR() {
        return _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeArray([
            _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeByteString(_util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_4__.BigintConverter.encode(this.sum)),
            this.hash.toCBOR(),
        ]);
    }
    toJSON() {
        return [this.sum.toString(), this.hash.toJSON()];
    }
    toString() {
        return `MerkleSumTreePathStepSibling[${this.sum},${this.hash.toString()}]`;
    }
}
class MerkleSumTreePathStepBranch {
    sum;
    _value;
    constructor(sum, _value) {
        this.sum = sum;
        this._value = _value;
        this._value = _value ? new Uint8Array(_value) : null;
    }
    get value() {
        return this._value ? new Uint8Array(this._value) : null;
    }
    static isJSON(data) {
        return Array.isArray(data);
    }
    static fromJSON(data) {
        if (!Array.isArray(data)) {
            throw new Error('Parsing merkle tree path step branch failed.');
        }
        const sum = data.at(0);
        const value = data.at(1);
        return new MerkleSumTreePathStepBranch(BigInt(sum ?? 0n), value ? _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_5__.HexConverter.decode(value) : null);
    }
    static fromCBOR(bytes) {
        const data = _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readArray(bytes);
        return new MerkleSumTreePathStepBranch(_util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_4__.BigintConverter.decode(_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readByteString(data[0])), _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readOptional(data[1], _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readByteString));
    }
    toCBOR() {
        return _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeArray([_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeOptional(this._value, _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeByteString)]);
    }
    toJSON() {
        return [this.sum.toString(), this._value ? _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_5__.HexConverter.encode(this._value) : null];
    }
    toString() {
        return `MerkleSumTreePathStepBranch[${this._value ? _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_5__.HexConverter.encode(this._value) : 'null'}]`;
    }
}
class MerkleSumTreePathStep {
    path;
    sibling;
    branch;
    constructor(path, sibling, branch) {
        this.path = path;
        this.sibling = sibling;
        this.branch = branch;
    }
    static createWithoutBranch(path, sibling) {
        return new MerkleSumTreePathStep(path, sibling ? MerkleSumTreePathStepSibling.create(sibling) : null, null);
    }
    static create(path, value, sibling) {
        if (value == null) {
            return new MerkleSumTreePathStep(path, sibling ? MerkleSumTreePathStepSibling.create(sibling) : null, new MerkleSumTreePathStepBranch(0n, null));
        }
        if (value instanceof _LeafBranch_js__WEBPACK_IMPORTED_MODULE_0__.LeafBranch) {
            return new MerkleSumTreePathStep(path, sibling ? MerkleSumTreePathStepSibling.create(sibling) : null, new MerkleSumTreePathStepBranch(value.sum, value.value));
        }
        return new MerkleSumTreePathStep(path, sibling ? MerkleSumTreePathStepSibling.create(sibling) : null, new MerkleSumTreePathStepBranch(value.sum, value.childrenHash.data));
    }
    static isJSON(data) {
        return (typeof data === 'object' &&
            data !== null &&
            'path' in data &&
            typeof data.path === 'string' &&
            'sibling' in data &&
            'branch' in data);
    }
    static fromJSON(data) {
        if (!MerkleSumTreePathStep.isJSON(data)) {
            throw new Error('Parsing merkle tree path step failed.');
        }
        return new MerkleSumTreePathStep(BigInt(data.path), data.sibling != null ? MerkleSumTreePathStepSibling.fromJSON(data.sibling) : null, data.branch != null ? MerkleSumTreePathStepBranch.fromJSON(data.branch) : null);
    }
    static fromCBOR(bytes) {
        const data = _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readArray(bytes);
        return new MerkleSumTreePathStep(_util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_4__.BigintConverter.decode(_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readByteString(data[0])), _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readOptional(data[1], MerkleSumTreePathStepSibling.fromCBOR), _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readOptional(data[2], MerkleSumTreePathStepBranch.fromCBOR));
    }
    toCBOR() {
        return _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeArray([
            _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeByteString(_util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_4__.BigintConverter.encode(this.path)),
            this.sibling?.toCBOR() ?? _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeNull(),
            this.branch?.toCBOR() ?? _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeNull(),
        ]);
    }
    toJSON() {
        return {
            branch: this.branch?.toJSON() ?? null,
            path: this.path.toString(),
            sibling: this.sibling?.toJSON() ?? null,
        };
    }
    toString() {
        return (0,_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_6__.dedent) `
      Merkle Tree Path Step
        Path: ${this.path.toString(2)}
        Branch: ${this.branch?.toString() ?? 'null'}
        Sibling: ${this.sibling?.toString() ?? 'null'}`;
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/smt/LeafBranch.js":
/*!*****************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/smt/LeafBranch.js ***!
  \*****************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   LeafBranch: () => (/* binding */ LeafBranch)
/* harmony export */ });
/* harmony import */ var _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ../util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");

class LeafBranch {
    path;
    _value;
    hash;
    constructor(path, _value, hash) {
        this.path = path;
        this._value = _value;
        this.hash = hash;
    }
    get value() {
        return new Uint8Array(this._value);
    }
    finalize() {
        return Promise.resolve(this);
    }
    toString() {
        return `
      Leaf[${this.path}]
        Value: ${_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_0__.HexConverter.encode(this._value)}
    `;
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/smt/MerkleTreePath.js":
/*!*********************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/smt/MerkleTreePath.js ***!
  \*********************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   MerkleTreePath: () => (/* binding */ MerkleTreePath)
/* harmony export */ });
/* harmony import */ var _MerkleTreePathStep_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./MerkleTreePathStep.js */ "./node_modules/@unicitylabs/commons/lib/smt/MerkleTreePathStep.js");
/* harmony import */ var _PathVerificationResult_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ./PathVerificationResult.js */ "./node_modules/@unicitylabs/commons/lib/smt/PathVerificationResult.js");
/* harmony import */ var _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ../cbor/CborDecoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborDecoder.js");
/* harmony import */ var _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! ../cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _hash_DataHash_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! ../hash/DataHash.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHash.js");
/* harmony import */ var _hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! ../hash/DataHasher.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHasher.js");
/* harmony import */ var _hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(/*! ../hash/HashAlgorithm.js */ "./node_modules/@unicitylabs/commons/lib/hash/HashAlgorithm.js");
/* harmony import */ var _util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_7__ = __webpack_require__(/*! ../util/BigintConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/BigintConverter.js");
/* harmony import */ var _util_StringUtils_js__WEBPACK_IMPORTED_MODULE_8__ = __webpack_require__(/*! ../util/StringUtils.js */ "./node_modules/@unicitylabs/commons/lib/util/StringUtils.js");









class MerkleTreePath {
    root;
    steps;
    constructor(root, steps) {
        this.root = root;
        this.steps = steps;
    }
    static fromJSON(data) {
        if (!MerkleTreePath.isJSON(data)) {
            throw new Error('Parsing merkle tree path json failed.');
        }
        return new MerkleTreePath(_hash_DataHash_js__WEBPACK_IMPORTED_MODULE_4__.DataHash.fromJSON(data.root), data.steps.map((step) => _MerkleTreePathStep_js__WEBPACK_IMPORTED_MODULE_0__.MerkleTreePathStep.fromJSON(step)));
    }
    static isJSON(data) {
        return (typeof data === 'object' &&
            data !== null &&
            'root' in data &&
            typeof data.root === 'string' &&
            'steps' in data &&
            Array.isArray(data.steps));
    }
    static fromCBOR(bytes) {
        const data = _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_2__.CborDecoder.readArray(bytes);
        const steps = _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_2__.CborDecoder.readArray(data[1]);
        return new MerkleTreePath(_hash_DataHash_js__WEBPACK_IMPORTED_MODULE_4__.DataHash.fromCBOR(data[0]), steps.map((step) => _MerkleTreePathStep_js__WEBPACK_IMPORTED_MODULE_0__.MerkleTreePathStep.fromCBOR(step)));
    }
    toCBOR() {
        return _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_3__.CborEncoder.encodeArray([
            this.root.toCBOR(),
            _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_3__.CborEncoder.encodeArray(this.steps.map((step) => step.toCBOR())),
        ]);
    }
    toJSON() {
        return {
            root: this.root.toJSON(),
            steps: this.steps.map((step) => step.toJSON()),
        };
    }
    async verify(requestId) {
        let currentPath = 1n;
        let currentHash = null;
        for (let i = 0; i < this.steps.length; i++) {
            const step = this.steps[i];
            let hash;
            if (step.branch === null) {
                hash = new Uint8Array(1);
            }
            else {
                const bytes = i === 0 ? step.branch.value : currentHash?.data;
                const digest = await new _hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_5__.DataHasher(_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_6__.HashAlgorithm.SHA256)
                    .update(_util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_7__.BigintConverter.encode(step.path))
                    .update(bytes ?? new Uint8Array(1))
                    .digest();
                hash = digest.data;
                const length = BigInt(step.path.toString(2).length - 1);
                currentPath = (currentPath << length) | (step.path & ((1n << length) - 1n));
            }
            const siblingHash = step.sibling?.data ?? new Uint8Array(1);
            const isRight = step.path & 1n;
            currentHash = await new _hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_5__.DataHasher(_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_6__.HashAlgorithm.SHA256)
                .update(isRight ? siblingHash : hash)
                .update(isRight ? hash : siblingHash)
                .digest();
        }
        return new _PathVerificationResult_js__WEBPACK_IMPORTED_MODULE_1__.PathVerificationResult(!!currentHash && this.root.equals(currentHash), requestId === currentPath);
    }
    toString() {
        return (0,_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_8__.dedent) `
      Merkle Tree Path
        Root: ${this.root.toString()} 
        Steps: [
          ${this.steps.map((step) => step?.toString() ?? 'null').join('\n')}
        ]`;
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/smt/MerkleTreePathStep.js":
/*!*************************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/smt/MerkleTreePathStep.js ***!
  \*************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   MerkleTreePathStep: () => (/* binding */ MerkleTreePathStep)
/* harmony export */ });
/* harmony import */ var _LeafBranch_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./LeafBranch.js */ "./node_modules/@unicitylabs/commons/lib/smt/LeafBranch.js");
/* harmony import */ var _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ../cbor/CborDecoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborDecoder.js");
/* harmony import */ var _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ../cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _hash_DataHash_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! ../hash/DataHash.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHash.js");
/* harmony import */ var _util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! ../util/BigintConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/BigintConverter.js");
/* harmony import */ var _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! ../util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");
/* harmony import */ var _util_StringUtils_js__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(/*! ../util/StringUtils.js */ "./node_modules/@unicitylabs/commons/lib/util/StringUtils.js");







class MerkleTreePathStepBranch {
    _value;
    constructor(_value) {
        this._value = _value;
        this._value = _value ? new Uint8Array(_value) : null;
    }
    get value() {
        return this._value ? new Uint8Array(this._value) : null;
    }
    static isJSON(data) {
        return Array.isArray(data);
    }
    static fromJSON(data) {
        if (!Array.isArray(data)) {
            throw new Error('Parsing merkle tree path step branch failed.');
        }
        const value = data.at(0);
        return new MerkleTreePathStepBranch(value ? _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_5__.HexConverter.decode(value) : null);
    }
    static fromCBOR(bytes) {
        const data = _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readArray(bytes);
        return new MerkleTreePathStepBranch(_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readOptional(data[0], _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readByteString));
    }
    toCBOR() {
        return _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeArray([_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeOptional(this._value, _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeByteString)]);
    }
    toJSON() {
        return this._value ? [_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_5__.HexConverter.encode(this._value)] : [];
    }
    toString() {
        return `MerkleTreePathStepBranch[${this._value ? _util_HexConverter_js__WEBPACK_IMPORTED_MODULE_5__.HexConverter.encode(this._value) : 'null'}]`;
    }
}
class MerkleTreePathStep {
    path;
    sibling;
    branch;
    constructor(path, sibling, branch) {
        this.path = path;
        this.sibling = sibling;
        this.branch = branch;
    }
    static createWithoutBranch(path, sibling) {
        return new MerkleTreePathStep(path, sibling?.hash ?? null, null);
    }
    static create(path, value, sibling) {
        if (value == null) {
            return new MerkleTreePathStep(path, sibling?.hash ?? null, new MerkleTreePathStepBranch(null));
        }
        if (value instanceof _LeafBranch_js__WEBPACK_IMPORTED_MODULE_0__.LeafBranch) {
            return new MerkleTreePathStep(path, sibling?.hash ?? null, new MerkleTreePathStepBranch(value.value));
        }
        return new MerkleTreePathStep(path, sibling?.hash ?? null, new MerkleTreePathStepBranch(value.childrenHash.data));
    }
    static isJSON(data) {
        return (typeof data === 'object' &&
            data !== null &&
            'path' in data &&
            typeof data.path === 'string' &&
            'sibling' in data &&
            'branch' in data);
    }
    static fromJSON(data) {
        if (!MerkleTreePathStep.isJSON(data)) {
            throw new Error('Parsing merkle tree path step failed.');
        }
        return new MerkleTreePathStep(BigInt(data.path), data.sibling == null ? null : _hash_DataHash_js__WEBPACK_IMPORTED_MODULE_3__.DataHash.fromJSON(data.sibling), data.branch != null ? MerkleTreePathStepBranch.fromJSON(data.branch) : null);
    }
    static fromCBOR(bytes) {
        const data = _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readArray(bytes);
        return new MerkleTreePathStep(_util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_4__.BigintConverter.decode(_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readByteString(data[0])), _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readOptional(data[1], _hash_DataHash_js__WEBPACK_IMPORTED_MODULE_3__.DataHash.fromCBOR), _cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_1__.CborDecoder.readOptional(data[2], MerkleTreePathStepBranch.fromCBOR));
    }
    toCBOR() {
        return _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeArray([
            _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeByteString(_util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_4__.BigintConverter.encode(this.path)),
            this.sibling?.toCBOR() ?? _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeNull(),
            this.branch?.toCBOR() ?? _cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeNull(),
        ]);
    }
    toJSON() {
        return {
            branch: this.branch?.toJSON() ?? null,
            path: this.path.toString(),
            sibling: this.sibling?.toJSON() ?? null,
        };
    }
    toString() {
        return (0,_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_6__.dedent) `
      Merkle Tree Path Step
        Path: ${this.path.toString(2)}
        Branch: ${this.branch?.toString() ?? 'null'}
        Sibling: ${this.sibling?.toString() ?? 'null'}`;
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/smt/PathVerificationResult.js":
/*!*****************************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/smt/PathVerificationResult.js ***!
  \*****************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   PathVerificationResult: () => (/* binding */ PathVerificationResult)
/* harmony export */ });
class PathVerificationResult {
    isPathValid;
    isPathIncluded;
    result;
    constructor(isPathValid, isPathIncluded) {
        this.isPathValid = isPathValid;
        this.isPathIncluded = isPathIncluded;
        this.result = isPathValid && isPathIncluded;
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/util/BigintConverter.js":
/*!***********************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/util/BigintConverter.js ***!
  \***********************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   BigintConverter: () => (/* binding */ BigintConverter)
/* harmony export */ });
class BigintConverter {
    /**
     * Convert bytes to unsigned long
     * @param {Uint8Array} data byte array
     * @param {Number} offset read offset
     * @param {Number} length read length
     * @returns {bigint} long value
     */
    static decode(data, offset, length) {
        offset = offset ?? 0;
        length = length ?? data.length;
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new Error('Index out of bounds');
        }
        let t = 0n;
        for (let i = 0; i < length; ++i) {
            t = (t << 8n) | BigInt(data[offset + i] & 0xff);
        }
        return t;
    }
    /**
     * Convert long to byte array
     * @param {bigint} value long value
     * @returns {Uint8Array} Array byte array
     */
    static encode(value) {
        const result = [];
        for (let t = value; t > 0n; t >>= 8n) {
            result.unshift(Number(t & 0xffn));
        }
        return new Uint8Array(result);
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js":
/*!********************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/util/HexConverter.js ***!
  \********************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   HexConverter: () => (/* binding */ HexConverter)
/* harmony export */ });
/* harmony import */ var _noble_hashes_utils__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @noble/hashes/utils */ "./node_modules/@noble/hashes/esm/utils.js");

class HexConverter {
    /**
     * Convert byte array to hex
     * @param {Uint8Array} data byte array
     * @returns string hex string
     */
    static encode(data) {
        return (0,_noble_hashes_utils__WEBPACK_IMPORTED_MODULE_0__.bytesToHex)(data);
    }
    /**
     * Convert hex string to bytes
     * @param value hex string
     * @returns {Uint8Array} byte array
     */
    static decode(value) {
        return (0,_noble_hashes_utils__WEBPACK_IMPORTED_MODULE_0__.hexToBytes)(value);
    }
}


/***/ }),

/***/ "./node_modules/@unicitylabs/commons/lib/util/StringUtils.js":
/*!*******************************************************************!*\
  !*** ./node_modules/@unicitylabs/commons/lib/util/StringUtils.js ***!
  \*******************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   dedent: () => (/* binding */ dedent)
/* harmony export */ });
/**
 * String dedent function, calculates distance which has to be removed from second line string
 * @param {TemplateStringsArray} strings - Template strings array
 * @param {unknown[]} data - Data to be inserted
 * @returns {string} - Dedented string
 */
function dedent(strings, ...data) {
    if (strings.length === 0) {
        return '';
    }
    let rows = strings[0].split('\n');
    if (rows.shift()?.length !== 0) {
        throw new Error('First line must be empty');
    }
    const whiteSpacesFromEdge = rows[0].length - rows[0].trimStart().length;
    const result = [];
    for (let j = 0; j < strings.length; j++) {
        result.push(`${result.pop() || ''}${rows[0].slice(Math.min(rows[0].length - rows[0].trim().length, whiteSpacesFromEdge))}`);
        for (let i = 1; i < rows.length; i++) {
            result.push(rows[i].slice(whiteSpacesFromEdge));
        }
        const lastElement = result.pop();
        const whiteSpaces = lastElement.length - lastElement.trimStart().length;
        const dataRows = j < data.length ? String(data[j]).split('\n') : [''];
        result.push(`${lastElement}${dataRows[0]}`);
        for (let i = 1; i < dataRows.length; i++) {
            result.push(`${' '.repeat(whiteSpaces)}${dataRows[i]}`);
        }
        rows = j + 1 < strings.length ? strings[j + 1].split('\n') : [];
    }
    return result.join('\n');
}


/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/ISerializable.js":
/*!*****************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/ISerializable.js ***!
  \*****************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);

//# sourceMappingURL=ISerializable.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/OfflineStateTransitionClient.js":
/*!********************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/OfflineStateTransitionClient.js ***!
  \********************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   OfflineStateTransitionClient: () => (/* binding */ OfflineStateTransitionClient)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_api_Authenticator_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/Authenticator.js */ "./node_modules/@unicitylabs/commons/lib/api/Authenticator.js");
/* harmony import */ var _unicitylabs_commons_lib_api_RequestId_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/RequestId.js */ "./node_modules/@unicitylabs/commons/lib/api/RequestId.js");
/* harmony import */ var _unicitylabs_commons_lib_api_SubmitCommitmentResponse_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/SubmitCommitmentResponse.js */ "./node_modules/@unicitylabs/commons/lib/api/SubmitCommitmentResponse.js");
/* harmony import */ var _StateTransitionClient_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! ./StateTransitionClient.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/StateTransitionClient.js");
/* harmony import */ var _transaction_Commitment_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! ./transaction/Commitment.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/Commitment.js");
/* harmony import */ var _transaction_OfflineCommitment_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! ./transaction/OfflineCommitment.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/OfflineCommitment.js");
/* harmony import */ var _utils_InclusionProofUtils_js__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(/*! ./utils/InclusionProofUtils.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/utils/InclusionProofUtils.js");







/**
 * High level client implementing the token state transition workflow.
 */
class OfflineStateTransitionClient extends _StateTransitionClient_js__WEBPACK_IMPORTED_MODULE_3__.StateTransitionClient {
    /**
     * Create an offline commitment for a transaction (does not post it to the aggregator).
     *
     * @param transactionData
     * @param signingService
     */
    async createOfflineCommitment(transactionData, signingService) {
        if (!(await transactionData.sourceState.unlockPredicate.isOwner(signingService.publicKey))) {
            throw new Error('Failed to unlock token');
        }
        const requestId = await _unicitylabs_commons_lib_api_RequestId_js__WEBPACK_IMPORTED_MODULE_1__.RequestId.create(signingService.publicKey, transactionData.sourceState.hash);
        const authenticator = await _unicitylabs_commons_lib_api_Authenticator_js__WEBPACK_IMPORTED_MODULE_0__.Authenticator.create(signingService, transactionData.hash, transactionData.sourceState.hash);
        return new _transaction_OfflineCommitment_js__WEBPACK_IMPORTED_MODULE_5__.OfflineCommitment(requestId, transactionData, authenticator);
    }
    /**
     * Submit an offline transaction commitment to the aggregator.
     *
     * @param requestId
     * @param transactionData
     * @param authenticator
     */
    async submitOfflineTransaction({ requestId, transactionData, authenticator, }) {
        const result = await this.client.submitTransaction(requestId, transactionData.hash, authenticator, false);
        if (result.status !== _unicitylabs_commons_lib_api_SubmitCommitmentResponse_js__WEBPACK_IMPORTED_MODULE_2__.SubmitCommitmentStatus.SUCCESS) {
            throw new Error(`Could not submit transaction: ${result.status}`);
        }
        const commitment = new _transaction_Commitment_js__WEBPACK_IMPORTED_MODULE_4__.Commitment(requestId, transactionData, authenticator);
        return await this.createTransaction(commitment, await (0,_utils_InclusionProofUtils_js__WEBPACK_IMPORTED_MODULE_6__.waitInclusionProof)(this, commitment));
    }
}
//# sourceMappingURL=OfflineStateTransitionClient.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/StateTransitionClient.js":
/*!*************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/StateTransitionClient.js ***!
  \*************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   MINTER_SECRET: () => (/* binding */ MINTER_SECRET),
/* harmony export */   StateTransitionClient: () => (/* binding */ StateTransitionClient)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_api_Authenticator_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/Authenticator.js */ "./node_modules/@unicitylabs/commons/lib/api/Authenticator.js");
/* harmony import */ var _unicitylabs_commons_lib_api_InclusionProof_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/InclusionProof.js */ "./node_modules/@unicitylabs/commons/lib/api/InclusionProof.js");
/* harmony import */ var _unicitylabs_commons_lib_api_RequestId_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/RequestId.js */ "./node_modules/@unicitylabs/commons/lib/api/RequestId.js");
/* harmony import */ var _unicitylabs_commons_lib_api_SubmitCommitmentResponse_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/SubmitCommitmentResponse.js */ "./node_modules/@unicitylabs/commons/lib/api/SubmitCommitmentResponse.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/HashAlgorithm.js */ "./node_modules/@unicitylabs/commons/lib/hash/HashAlgorithm.js");
/* harmony import */ var _unicitylabs_commons_lib_signing_SigningService_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! @unicitylabs/commons/lib/signing/SigningService.js */ "./node_modules/@unicitylabs/commons/lib/signing/SigningService.js");
/* harmony import */ var _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");
/* harmony import */ var _address_DirectAddress_js__WEBPACK_IMPORTED_MODULE_7__ = __webpack_require__(/*! ./address/DirectAddress.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/address/DirectAddress.js");
/* harmony import */ var _token_Token_js__WEBPACK_IMPORTED_MODULE_8__ = __webpack_require__(/*! ./token/Token.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/Token.js");
/* harmony import */ var _transaction_Commitment_js__WEBPACK_IMPORTED_MODULE_9__ = __webpack_require__(/*! ./transaction/Commitment.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/Commitment.js");
/* harmony import */ var _transaction_Transaction_js__WEBPACK_IMPORTED_MODULE_10__ = __webpack_require__(/*! ./transaction/Transaction.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/Transaction.js");











// I_AM_UNIVERSAL_MINTER_FOR_ string bytes
/**
 * Secret prefix for the signing used internally when minting tokens.
 */
const MINTER_SECRET = _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_6__.HexConverter.decode('495f414d5f554e4956455253414c5f4d494e5445525f464f525f');
/**
 * High level client implementing the token state transition workflow.
 */
class StateTransitionClient {
    client;
    /**
     * @param client Implementation used to talk to an aggregator
     */
    constructor(client) {
        this.client = client;
    }
    /**
     * Create and submit a mint transaction for a new token.
     * @param transactionData Mint transaction data containing token information and address.
     * @returns Commitment containing the transaction data and authenticator
     * @throws Error when the aggregator rejects the transaction
     *
     * @example
     * ```ts
     * const commitment = await client.submitMintTransaction(
     *   await MintTransactionData.create(
     *     TokenId.create(crypto.getRandomValues(new Uint8Array(32))),
     *     TokenType.create(crypto.getRandomValues(new Uint8Array(32))),
     *     new Uint8Array(),
     *     null,
     *     await DirectAddress.create(mintTokenData.predicate.reference),
     *     crypto.getRandomValues(new Uint8Array(32)),
     *     null,
     *     null
     *   )
     * );
     * ```
     */
    async submitMintTransaction(transactionData) {
        return this.sendTransaction(transactionData, await _unicitylabs_commons_lib_signing_SigningService_js__WEBPACK_IMPORTED_MODULE_5__.SigningService.createFromSecret(MINTER_SECRET, transactionData.tokenId.bytes));
    }
    /**
     * Submit a state transition for an existing token.
     *
     * @param transactionData Data describing the transition
     * @param signingService   Signing service for the current owner
     * @returns Commitment ready for inclusion proof retrieval
     * @throws Error if ownership verification fails or aggregator rejects
     *
     * @example
     * ```ts
     * const commitment = await client.submitTransaction(data, signingService);
     * ```
     */
    async submitTransaction(transactionData, signingService) {
        if (!(await transactionData.sourceState.unlockPredicate.isOwner(signingService.publicKey))) {
            throw new Error('Failed to unlock token');
        }
        return this.sendTransaction(transactionData, signingService);
    }
    /**
     * Build a {@link Transaction} object once an inclusion proof is obtained.
     *
     * @param param0       Commitment returned from submit* methods
     * @param inclusionProof Proof of inclusion from the aggregator
     * @returns Constructed transaction object
     * @throws Error if the inclusion proof is invalid
     *
     * @example
     * ```ts
     * const tx = await client.createTransaction(commitment, inclusionProof);
     * ```
     */
    async createTransaction({ requestId, transactionData }, inclusionProof) {
        const status = await inclusionProof.verify(requestId.toBigInt());
        if (status != _unicitylabs_commons_lib_api_InclusionProof_js__WEBPACK_IMPORTED_MODULE_1__.InclusionProofVerificationStatus.OK) {
            throw new Error('Inclusion proof verification failed.');
        }
        if (!inclusionProof.authenticator || !_unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_4__.HashAlgorithm[inclusionProof.authenticator.stateHash.algorithm]) {
            throw new Error('Invalid inclusion proof hash algorithm.');
        }
        if (!inclusionProof.transactionHash?.equals(transactionData.hash)) {
            throw new Error('Payload hash mismatch');
        }
        return new _transaction_Transaction_js__WEBPACK_IMPORTED_MODULE_10__.Transaction(transactionData, inclusionProof);
    }
    /**
     * Finalise a transaction and produce the next token state.
     *
     * @param token           Token being transitioned
     * @param state           New state after the transition
     * @param transaction     Transaction proving the state change
     * @param nametagTokens   Optional name tag tokens associated with the transfer
     * @returns Updated token instance
     * @throws Error if validation checks fail
     *
     * @example
     * ```ts
     * const updated = await client.finishTransaction(token, state, tx);
     * ```
     */
    async finishTransaction(token, state, transaction, nametagTokens = []) {
        if (!(await transaction.data.sourceState.unlockPredicate.verify(transaction))) {
            throw new Error('Predicate verification failed');
        }
        // TODO: Move address processing to a separate method
        // TODO: Resolve proxy address
        const expectedAddress = await _address_DirectAddress_js__WEBPACK_IMPORTED_MODULE_7__.DirectAddress.create(state.unlockPredicate.reference);
        if (expectedAddress.toJSON() !== transaction.data.recipient) {
            throw new Error('Recipient address mismatch');
        }
        const transactions = [...token.transactions, transaction];
        if (!(await transaction.containsData(state.data))) {
            throw new Error('State data is not part of transaction.');
        }
        return new _token_Token_js__WEBPACK_IMPORTED_MODULE_8__.Token(state, token.genesis, transactions, nametagTokens);
    }
    /**
     * Query the ledger to see if the token's current state has been spent.
     *
     * @param token     Token to check
     * @param publicKey Public key of the owner
     * @returns Verification status reported by the aggregator
     *
     * @example
     * ```ts
     * const status = await client.getTokenStatus(token, ownerPublicKey);
     * ```
     */
    async getTokenStatus(token, publicKey) {
        const requestId = await _unicitylabs_commons_lib_api_RequestId_js__WEBPACK_IMPORTED_MODULE_2__.RequestId.create(publicKey, token.state.hash);
        const inclusionProof = await this.client.getInclusionProof(requestId);
        // TODO: Check ownership?
        return inclusionProof.verify(requestId.toBigInt());
    }
    /**
     * Convenience helper to retrieve the inclusion proof for a commitment.
     *
     * @example
     * ```ts
     * const proof = await client.getInclusionProof(commitment);
     * ```
     */
    getInclusionProof(commitment) {
        return this.client.getInclusionProof(commitment.requestId);
    }
    async sendTransaction(transactionData, signingService) {
        const requestId = await _unicitylabs_commons_lib_api_RequestId_js__WEBPACK_IMPORTED_MODULE_2__.RequestId.create(signingService.publicKey, transactionData.sourceState.hash);
        const authenticator = await _unicitylabs_commons_lib_api_Authenticator_js__WEBPACK_IMPORTED_MODULE_0__.Authenticator.create(signingService, transactionData.hash, transactionData.sourceState.hash);
        const result = await this.client.submitTransaction(requestId, transactionData.hash, authenticator);
        if (result.status !== _unicitylabs_commons_lib_api_SubmitCommitmentResponse_js__WEBPACK_IMPORTED_MODULE_3__.SubmitCommitmentStatus.SUCCESS) {
            throw new Error(`Could not submit transaction: ${result.status}`);
        }
        return new _transaction_Commitment_js__WEBPACK_IMPORTED_MODULE_9__.Commitment(requestId, transactionData, authenticator);
    }
}
//# sourceMappingURL=StateTransitionClient.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/address/AddressScheme.js":
/*!*************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/address/AddressScheme.js ***!
  \*************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   AddressScheme: () => (/* binding */ AddressScheme)
/* harmony export */ });
/**
 * Enum representing different address schemes.
 */
var AddressScheme;
(function (AddressScheme) {
    /** Direct address pointing to a predicate reference. */
    AddressScheme["DIRECT"] = "DIRECT";
    /** Address pointing to a proxy object such as a name tag. */
    AddressScheme["PROXY"] = "PROXY";
})(AddressScheme || (AddressScheme = {}));
//# sourceMappingURL=AddressScheme.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/address/DirectAddress.js":
/*!*************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/address/DirectAddress.js ***!
  \*************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   DirectAddress: () => (/* binding */ DirectAddress)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_DataHash_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/DataHash.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHash.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/DataHasher.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHasher.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/HashAlgorithm.js */ "./node_modules/@unicitylabs/commons/lib/hash/HashAlgorithm.js");
/* harmony import */ var _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");
/* harmony import */ var _AddressScheme_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! ./AddressScheme.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/address/AddressScheme.js");






/**
 * Address that directly references a predicate.
 *
 * This address type is used to point to a specific predicate by its reference hash.
 * It includes a checksum to help detect mistyped addresses.
 */
class DirectAddress {
    data;
    checksum;
    /**
     * Create a new {@link DirectAddress} instance.
     *
     * @param data     Reference to the predicate this address points to
     * @param checksum 4-byte checksum to detect mistyped addresses
     */
    constructor(data, checksum) {
        this.data = data;
        this.checksum = checksum;
        this.checksum = new Uint8Array(checksum.slice(0, 4));
    }
    /**
     * @inheritDoc
     */
    get scheme() {
        return _AddressScheme_js__WEBPACK_IMPORTED_MODULE_5__.AddressScheme.DIRECT;
    }
    /**
     * Build a direct address from a predicate reference.
     *
     * @param predicateReference The predicate reference to encode
     * @returns Newly created address instance
     */
    static async create(predicateReference) {
        const checksum = await new _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_2__.DataHasher(_unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_3__.HashAlgorithm.SHA256).update(predicateReference.toCBOR()).digest();
        return new DirectAddress(predicateReference, checksum.data.slice(0, 4));
    }
    /**
     * Create new DirectAddress from string.
     * @param data Address as string.
     */
    static async fromJSON(data) {
        const [scheme, uri] = data.split('://');
        if (scheme !== _AddressScheme_js__WEBPACK_IMPORTED_MODULE_5__.AddressScheme.DIRECT) {
            throw new Error(`Invalid address scheme: expected ${_AddressScheme_js__WEBPACK_IMPORTED_MODULE_5__.AddressScheme.DIRECT}, got ${scheme}`);
        }
        const checksum = uri.slice(-8);
        const address = await DirectAddress.create(_unicitylabs_commons_lib_hash_DataHash_js__WEBPACK_IMPORTED_MODULE_1__.DataHash.fromCBOR(_unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.decode(uri.slice(0, -8))));
        if (_unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(address.checksum) !== checksum) {
            throw new Error(`Invalid checksum for DirectAddress: expected ${checksum}, got ${_unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(address.checksum)}`);
        }
        return address;
    }
    /**
     * Convert the address into its canonical string form.
     */
    toJSON() {
        return this.toString();
    }
    /**
     * Encode the address as a CBOR text string.
     */
    toCBOR() {
        return _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeTextString(this.toString());
    }
    /** Convert instance to readable string */
    toString() {
        return `${this.scheme}://${_unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(this.data.toCBOR())}${_unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(this.checksum)}`;
    }
}
//# sourceMappingURL=DirectAddress.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/address/IAddress.js":
/*!********************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/address/IAddress.js ***!
  \********************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);

//# sourceMappingURL=IAddress.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/api/AggregatorClient.js":
/*!************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/api/AggregatorClient.js ***!
  \************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   AggregatorClient: () => (/* binding */ AggregatorClient)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_api_InclusionProof_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/InclusionProof.js */ "./node_modules/@unicitylabs/commons/lib/api/InclusionProof.js");
/* harmony import */ var _unicitylabs_commons_lib_api_SubmitCommitmentRequest_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/SubmitCommitmentRequest.js */ "./node_modules/@unicitylabs/commons/lib/api/SubmitCommitmentRequest.js");
/* harmony import */ var _unicitylabs_commons_lib_api_SubmitCommitmentResponse_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/SubmitCommitmentResponse.js */ "./node_modules/@unicitylabs/commons/lib/api/SubmitCommitmentResponse.js");
/* harmony import */ var _unicitylabs_commons_lib_json_rpc_JsonRpcHttpTransport_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! @unicitylabs/commons/lib/json-rpc/JsonRpcHttpTransport.js */ "./node_modules/@unicitylabs/commons/lib/json-rpc/JsonRpcHttpTransport.js");




/**
 * Client implementation for communicating with an aggregator via JSON-RPC.
 */
class AggregatorClient {
    transport;
    /**
     * Create a new client pointing to the given aggregator URL.
     *
     * @param url Base URL of the aggregator JSON-RPC endpoint
     */
    constructor(url) {
        this.transport = new _unicitylabs_commons_lib_json_rpc_JsonRpcHttpTransport_js__WEBPACK_IMPORTED_MODULE_3__.JsonRpcHttpTransport(url);
    }
    /**
     * @inheritDoc
     */
    async submitTransaction(requestId, transactionHash, authenticator, receipt = false) {
        const request = new _unicitylabs_commons_lib_api_SubmitCommitmentRequest_js__WEBPACK_IMPORTED_MODULE_1__.SubmitCommitmentRequest(requestId, transactionHash, authenticator, receipt);
        const response = await this.transport.request('submit_commitment', request.toJSON());
        return _unicitylabs_commons_lib_api_SubmitCommitmentResponse_js__WEBPACK_IMPORTED_MODULE_2__.SubmitCommitmentResponse.fromJSON(response);
    }
    /**
     * @inheritDoc
     */
    async getInclusionProof(requestId, blockNum) {
        const data = { blockNum: blockNum?.toString(), requestId: requestId.toJSON() };
        return _unicitylabs_commons_lib_api_InclusionProof_js__WEBPACK_IMPORTED_MODULE_0__.InclusionProof.fromJSON(await this.transport.request('get_inclusion_proof', data));
    }
    /**
     * Fetch a proof that the given request has not been deleted from the ledger.
     *
     * @param requestId Request identifier
     */
    getNoDeletionProof(requestId) {
        const data = { requestId: requestId.toJSON() };
        return this.transport.request('get_no_deletion_proof', data);
    }
    async getBlockHeight() {
        const response = await this.transport.request('get_block_height', {});
        if (response &&
            typeof response === 'object' &&
            'blockNumber' in response &&
            (typeof response.blockNumber === 'string' ||
                typeof response.blockNumber === 'number' ||
                typeof response.blockNumber === 'bigint')) {
            return BigInt(response.blockNumber);
        }
        throw new Error('Invalid response format for block height');
    }
}
//# sourceMappingURL=AggregatorClient.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/api/IAggregatorClient.js":
/*!*************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/api/IAggregatorClient.js ***!
  \*************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);

//# sourceMappingURL=IAggregatorClient.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/index.js":
/*!*********************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/index.js ***!
  \*********************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   AddressScheme: () => (/* reexport safe */ _address_AddressScheme_js__WEBPACK_IMPORTED_MODULE_0__.AddressScheme),
/* harmony export */   AggregatorClient: () => (/* reexport safe */ _api_AggregatorClient_js__WEBPACK_IMPORTED_MODULE_3__.AggregatorClient),
/* harmony export */   BurnPredicate: () => (/* reexport safe */ _predicate_BurnPredicate_js__WEBPACK_IMPORTED_MODULE_5__.BurnPredicate),
/* harmony export */   CoinId: () => (/* reexport safe */ _token_fungible_CoinId_js__WEBPACK_IMPORTED_MODULE_21__.CoinId),
/* harmony export */   Commitment: () => (/* reexport safe */ _transaction_Commitment_js__WEBPACK_IMPORTED_MODULE_22__.Commitment),
/* harmony export */   DefaultPredicate: () => (/* reexport safe */ _predicate_DefaultPredicate_js__WEBPACK_IMPORTED_MODULE_6__.DefaultPredicate),
/* harmony export */   DirectAddress: () => (/* reexport safe */ _address_DirectAddress_js__WEBPACK_IMPORTED_MODULE_1__.DirectAddress),
/* harmony export */   MINTER_SECRET: () => (/* reexport safe */ _StateTransitionClient_js__WEBPACK_IMPORTED_MODULE_27__.MINTER_SECRET),
/* harmony export */   MaskedPredicate: () => (/* reexport safe */ _predicate_MaskedPredicate_js__WEBPACK_IMPORTED_MODULE_9__.MaskedPredicate),
/* harmony export */   MintTransactionData: () => (/* reexport safe */ _transaction_MintTransactionData_js__WEBPACK_IMPORTED_MODULE_23__.MintTransactionData),
/* harmony export */   NameTagTokenData: () => (/* reexport safe */ _token_NameTagTokenData_js__WEBPACK_IMPORTED_MODULE_14__.NameTagTokenData),
/* harmony export */   PredicateJsonFactory: () => (/* reexport safe */ _predicate_PredicateJsonFactory_js__WEBPACK_IMPORTED_MODULE_10__.PredicateJsonFactory),
/* harmony export */   PredicateType: () => (/* reexport safe */ _predicate_PredicateType_js__WEBPACK_IMPORTED_MODULE_11__.PredicateType),
/* harmony export */   StateTransitionClient: () => (/* reexport safe */ _StateTransitionClient_js__WEBPACK_IMPORTED_MODULE_27__.StateTransitionClient),
/* harmony export */   TOKEN_VERSION: () => (/* reexport safe */ _token_Token_js__WEBPACK_IMPORTED_MODULE_15__.TOKEN_VERSION),
/* harmony export */   Token: () => (/* reexport safe */ _token_Token_js__WEBPACK_IMPORTED_MODULE_15__.Token),
/* harmony export */   TokenCoinData: () => (/* reexport safe */ _token_fungible_TokenCoinData_js__WEBPACK_IMPORTED_MODULE_20__.TokenCoinData),
/* harmony export */   TokenFactory: () => (/* reexport safe */ _token_TokenFactory_js__WEBPACK_IMPORTED_MODULE_16__.TokenFactory),
/* harmony export */   TokenId: () => (/* reexport safe */ _token_TokenId_js__WEBPACK_IMPORTED_MODULE_17__.TokenId),
/* harmony export */   TokenState: () => (/* reexport safe */ _token_TokenState_js__WEBPACK_IMPORTED_MODULE_18__.TokenState),
/* harmony export */   TokenType: () => (/* reexport safe */ _token_TokenType_js__WEBPACK_IMPORTED_MODULE_19__.TokenType),
/* harmony export */   Transaction: () => (/* reexport safe */ _transaction_Transaction_js__WEBPACK_IMPORTED_MODULE_24__.Transaction),
/* harmony export */   TransactionData: () => (/* reexport safe */ _transaction_TransactionData_js__WEBPACK_IMPORTED_MODULE_25__.TransactionData),
/* harmony export */   UnmaskedPredicate: () => (/* reexport safe */ _predicate_UnmaskedPredicate_js__WEBPACK_IMPORTED_MODULE_12__.UnmaskedPredicate)
/* harmony export */ });
/* harmony import */ var _address_AddressScheme_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./address/AddressScheme.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/address/AddressScheme.js");
/* harmony import */ var _address_DirectAddress_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ./address/DirectAddress.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/address/DirectAddress.js");
/* harmony import */ var _address_IAddress_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ./address/IAddress.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/address/IAddress.js");
/* harmony import */ var _api_AggregatorClient_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! ./api/AggregatorClient.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/api/AggregatorClient.js");
/* harmony import */ var _api_IAggregatorClient_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! ./api/IAggregatorClient.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/api/IAggregatorClient.js");
/* harmony import */ var _predicate_BurnPredicate_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! ./predicate/BurnPredicate.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/BurnPredicate.js");
/* harmony import */ var _predicate_DefaultPredicate_js__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(/*! ./predicate/DefaultPredicate.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/DefaultPredicate.js");
/* harmony import */ var _predicate_IPredicate_js__WEBPACK_IMPORTED_MODULE_7__ = __webpack_require__(/*! ./predicate/IPredicate.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/IPredicate.js");
/* harmony import */ var _predicate_IPredicateFactory_js__WEBPACK_IMPORTED_MODULE_8__ = __webpack_require__(/*! ./predicate/IPredicateFactory.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/IPredicateFactory.js");
/* harmony import */ var _predicate_MaskedPredicate_js__WEBPACK_IMPORTED_MODULE_9__ = __webpack_require__(/*! ./predicate/MaskedPredicate.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/MaskedPredicate.js");
/* harmony import */ var _predicate_PredicateJsonFactory_js__WEBPACK_IMPORTED_MODULE_10__ = __webpack_require__(/*! ./predicate/PredicateJsonFactory.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/PredicateJsonFactory.js");
/* harmony import */ var _predicate_PredicateType_js__WEBPACK_IMPORTED_MODULE_11__ = __webpack_require__(/*! ./predicate/PredicateType.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/PredicateType.js");
/* harmony import */ var _predicate_UnmaskedPredicate_js__WEBPACK_IMPORTED_MODULE_12__ = __webpack_require__(/*! ./predicate/UnmaskedPredicate.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/UnmaskedPredicate.js");
/* harmony import */ var _token_NameTagToken_js__WEBPACK_IMPORTED_MODULE_13__ = __webpack_require__(/*! ./token/NameTagToken.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/NameTagToken.js");
/* harmony import */ var _token_NameTagTokenData_js__WEBPACK_IMPORTED_MODULE_14__ = __webpack_require__(/*! ./token/NameTagTokenData.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/NameTagTokenData.js");
/* harmony import */ var _token_Token_js__WEBPACK_IMPORTED_MODULE_15__ = __webpack_require__(/*! ./token/Token.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/Token.js");
/* harmony import */ var _token_TokenFactory_js__WEBPACK_IMPORTED_MODULE_16__ = __webpack_require__(/*! ./token/TokenFactory.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/TokenFactory.js");
/* harmony import */ var _token_TokenId_js__WEBPACK_IMPORTED_MODULE_17__ = __webpack_require__(/*! ./token/TokenId.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/TokenId.js");
/* harmony import */ var _token_TokenState_js__WEBPACK_IMPORTED_MODULE_18__ = __webpack_require__(/*! ./token/TokenState.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/TokenState.js");
/* harmony import */ var _token_TokenType_js__WEBPACK_IMPORTED_MODULE_19__ = __webpack_require__(/*! ./token/TokenType.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/TokenType.js");
/* harmony import */ var _token_fungible_TokenCoinData_js__WEBPACK_IMPORTED_MODULE_20__ = __webpack_require__(/*! ./token/fungible/TokenCoinData.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/fungible/TokenCoinData.js");
/* harmony import */ var _token_fungible_CoinId_js__WEBPACK_IMPORTED_MODULE_21__ = __webpack_require__(/*! ./token/fungible/CoinId.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/fungible/CoinId.js");
/* harmony import */ var _transaction_Commitment_js__WEBPACK_IMPORTED_MODULE_22__ = __webpack_require__(/*! ./transaction/Commitment.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/Commitment.js");
/* harmony import */ var _transaction_MintTransactionData_js__WEBPACK_IMPORTED_MODULE_23__ = __webpack_require__(/*! ./transaction/MintTransactionData.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/MintTransactionData.js");
/* harmony import */ var _transaction_Transaction_js__WEBPACK_IMPORTED_MODULE_24__ = __webpack_require__(/*! ./transaction/Transaction.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/Transaction.js");
/* harmony import */ var _transaction_TransactionData_js__WEBPACK_IMPORTED_MODULE_25__ = __webpack_require__(/*! ./transaction/TransactionData.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/TransactionData.js");
/* harmony import */ var _ISerializable_js__WEBPACK_IMPORTED_MODULE_26__ = __webpack_require__(/*! ./ISerializable.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/ISerializable.js");
/* harmony import */ var _StateTransitionClient_js__WEBPACK_IMPORTED_MODULE_27__ = __webpack_require__(/*! ./StateTransitionClient.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/StateTransitionClient.js");
// Address exports



// API exports


// Predicate exports








// Token exports







// Fungible token exports


// Transaction exports




// Core exports


//# sourceMappingURL=index.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/BurnPredicate.js":
/*!***************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/BurnPredicate.js ***!
  \***************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   BurnPredicate: () => (/* binding */ BurnPredicate)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborDecoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborDecoder.js");
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_DataHash_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/DataHash.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHash.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/DataHasher.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHasher.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/HashAlgorithm.js */ "./node_modules/@unicitylabs/commons/lib/hash/HashAlgorithm.js");
/* harmony import */ var _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");
/* harmony import */ var _unicitylabs_commons_lib_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/StringUtils.js */ "./node_modules/@unicitylabs/commons/lib/util/StringUtils.js");
/* harmony import */ var _PredicateType_js__WEBPACK_IMPORTED_MODULE_7__ = __webpack_require__(/*! ./PredicateType.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/PredicateType.js");








const TYPE = _PredicateType_js__WEBPACK_IMPORTED_MODULE_7__.PredicateType.BURN;
/**
 * Predicate representing a permanently burned token.
 */
class BurnPredicate {
    reference;
    hash;
    _nonce;
    reason;
    type = TYPE;
    /**
     * @param reference  Reference hash identifying the predicate
     * @param hash       Unique hash of the predicate and token
     * @param _nonce     Nonce used to ensure uniqueness
     * @param reason     Reason for the burn
     */
    constructor(reference, hash, _nonce, reason) {
        this.reference = reference;
        this.hash = hash;
        this._nonce = _nonce;
        this.reason = reason;
    }
    /** @inheritDoc */
    get nonce() {
        return new Uint8Array(this._nonce);
    }
    /**
     * Create a new burn predicate.
     * @param tokenId Token ID for which the predicate is valid.
     * @param tokenType Type of the token.
     * @param nonce Nonce providing uniqueness for the predicate.
     * @param burnReason Burn reason for committing to the new tokens and coins being created after the burn.
     */
    static async create(tokenId, tokenType, nonce, burnReason) {
        const reference = await BurnPredicate.calculateReference(tokenType, burnReason);
        const hash = await BurnPredicate.calculateHash(reference, tokenId, nonce);
        return new BurnPredicate(reference, hash, nonce, burnReason);
    }
    /**
     * Create a burn predicate from JSON data.
     * @param tokenId Token ID for which the predicate is valid.
     * @param tokenType Type of the token.
     * @param data JSON data representing the burn predicate.
     */
    static fromJSON(tokenId, tokenType, data) {
        if (!BurnPredicate.isJSON(data)) {
            throw new Error('Invalid burn predicate json');
        }
        return BurnPredicate.create(tokenId, tokenType, _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_5__.HexConverter.decode(data.nonce), _unicitylabs_commons_lib_hash_DataHash_js__WEBPACK_IMPORTED_MODULE_2__.DataHash.fromJSON(data.reason));
    }
    static fromCBOR(tokenId, tokenType, bytes) {
        const data = _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__.CborDecoder.readArray(bytes);
        const type = _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__.CborDecoder.readTextString(data[0]);
        if (type !== _PredicateType_js__WEBPACK_IMPORTED_MODULE_7__.PredicateType.BURN) {
            throw new Error(`Invalid predicate type: expected ${_PredicateType_js__WEBPACK_IMPORTED_MODULE_7__.PredicateType.BURN}, got ${type}`);
        }
        return BurnPredicate.create(tokenId, tokenType, _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__.CborDecoder.readByteString(data[1]), _unicitylabs_commons_lib_hash_DataHash_js__WEBPACK_IMPORTED_MODULE_2__.DataHash.fromCBOR(data[2]));
    }
    /**
     * Calculate the reference hash for a burn predicate.
     * @param tokenType Type of the token for which the predicate is valid.
     * @param burnReason Reason for the burn
     */
    static calculateReference(tokenType, burnReason) {
        return new _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_3__.DataHasher(_unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_4__.HashAlgorithm.SHA256)
            .update(_unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeArray([_unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeTextString(TYPE), tokenType.toCBOR(), burnReason.toCBOR()]))
            .digest();
    }
    /**
     * Check if the provided data is a valid JSON representation of a burn predicate.
     * @param data Data to validate.
     */
    static isJSON(data) {
        return (typeof data === 'object' &&
            data !== null &&
            'type' in data &&
            data.type === _PredicateType_js__WEBPACK_IMPORTED_MODULE_7__.PredicateType.BURN &&
            'nonce' in data &&
            typeof data.nonce === 'string' &&
            'reason' in data &&
            typeof data.reason === 'string');
    }
    /**
     * Compute the predicate hash for a specific token and nonce.
     * @param reference Reference hash of the predicate.
     * @param tokenId Token ID for which the predicate is valid.
     * @param nonce Nonce providing uniqueness for the predicate.
     * @private
     */
    static calculateHash(reference, tokenId, nonce) {
        return new _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_3__.DataHasher(_unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_4__.HashAlgorithm.SHA256)
            .update(_unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeArray([reference.toCBOR(), tokenId.toCBOR(), _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeByteString(nonce)]))
            .digest();
    }
    /** @inheritDoc */
    toJSON() {
        return {
            nonce: _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_5__.HexConverter.encode(this._nonce),
            reason: this.reason.toJSON(),
            type: this.type,
        };
    }
    /** @inheritDoc */
    toCBOR() {
        return _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeArray([
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeTextString(this.type),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeByteString(this._nonce),
            this.reason.toCBOR(),
        ]);
    }
    /** @inheritDoc */
    verify() {
        return Promise.resolve(false);
    }
    /** Convert instance to readable string */
    toString() {
        return (0,_unicitylabs_commons_lib_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_6__.dedent) `
          Predicate[${this.type}]:
            Hash: ${this.hash.toString()}`;
    }
    /** @inheritDoc */
    isOwner() {
        return Promise.resolve(false);
    }
}
//# sourceMappingURL=BurnPredicate.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/DefaultPredicate.js":
/*!******************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/DefaultPredicate.js ***!
  \******************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   DefaultPredicate: () => (/* binding */ DefaultPredicate)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_api_InclusionProof_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/InclusionProof.js */ "./node_modules/@unicitylabs/commons/lib/api/InclusionProof.js");
/* harmony import */ var _unicitylabs_commons_lib_api_RequestId_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/RequestId.js */ "./node_modules/@unicitylabs/commons/lib/api/RequestId.js");
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/HashAlgorithm.js */ "./node_modules/@unicitylabs/commons/lib/hash/HashAlgorithm.js");
/* harmony import */ var _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");
/* harmony import */ var _unicitylabs_commons_lib_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/StringUtils.js */ "./node_modules/@unicitylabs/commons/lib/util/StringUtils.js");






/**
 * Base predicate containing common verification logic for key-based predicates.
 */
class DefaultPredicate {
    type;
    _publicKey;
    algorithm;
    hashAlgorithm;
    _nonce;
    reference;
    hash;
    /**
     * @param type          Predicate type value
     * @param _publicKey    Public key able to sign transactions
     * @param algorithm     Signing algorithm name
     * @param hashAlgorithm Hash algorithm used for hashing operations
     * @param _nonce        Nonce providing uniqueness
     * @param reference     Reference hash of the predicate
     * @param hash          Hash of the predicate with a specific token
     */
    constructor(type, _publicKey, algorithm, hashAlgorithm, _nonce, reference, hash) {
        this.type = type;
        this._publicKey = _publicKey;
        this.algorithm = algorithm;
        this.hashAlgorithm = hashAlgorithm;
        this._nonce = _nonce;
        this.reference = reference;
        this.hash = hash;
        this._publicKey = new Uint8Array(_publicKey);
        this._nonce = new Uint8Array(_nonce);
    }
    /** Public key associated with the predicate. */
    get publicKey() {
        return this._publicKey;
    }
    /**
     * @inheritDoc
     */
    get nonce() {
        return this._nonce;
    }
    /**
     * Check if the provided data is a valid JSON representation of a key based predicate.
     * @param data Data to validate.
     */
    static isJSON(data) {
        return (typeof data === 'object' &&
            data !== null &&
            'publicKey' in data &&
            typeof data.publicKey === 'string' &&
            'algorithm' in data &&
            typeof data.algorithm === 'string' &&
            'hashAlgorithm' in data &&
            !!_unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_3__.HashAlgorithm[data.hashAlgorithm] &&
            'nonce' in data &&
            typeof data.nonce === 'string');
    }
    /**
     * @inheritDoc
     */
    toJSON() {
        return {
            algorithm: this.algorithm,
            hashAlgorithm: this.hashAlgorithm,
            nonce: _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(this.nonce),
            publicKey: _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(this.publicKey),
            type: this.type,
        };
    }
    /**
     * @inheritDoc
     */
    toCBOR() {
        return _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeArray([
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeTextString(this.type),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeByteString(this.publicKey),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeTextString(this.algorithm),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeTextString(_unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_3__.HashAlgorithm[this.hashAlgorithm]),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeByteString(this.nonce),
        ]);
    }
    /**
     * @inheritDoc
     */
    async verify(transaction) {
        if (!transaction.inclusionProof.authenticator || !transaction.inclusionProof.transactionHash) {
            return false;
        }
        // Verify if input state and public key are correct.
        if (_unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(transaction.inclusionProof.authenticator.publicKey) !== _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(this.publicKey) ||
            !transaction.inclusionProof.authenticator.stateHash.equals(transaction.data.sourceState.hash)) {
            return false; // input mismatch
        }
        // Verify if transaction data is valid.
        if (!(await transaction.inclusionProof.authenticator.verify(transaction.data.hash))) {
            return false;
        }
        // Verify inclusion proof path.
        const requestId = await _unicitylabs_commons_lib_api_RequestId_js__WEBPACK_IMPORTED_MODULE_1__.RequestId.create(this.publicKey, transaction.data.sourceState.hash);
        const status = await transaction.inclusionProof.verify(requestId.toBigInt());
        return status === _unicitylabs_commons_lib_api_InclusionProof_js__WEBPACK_IMPORTED_MODULE_0__.InclusionProofVerificationStatus.OK;
    }
    /** Convert instance to readable string */
    toString() {
        return (0,_unicitylabs_commons_lib_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_5__.dedent) `
          Predicate[${this.type}]:
            PublicKey: ${_unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(this.publicKey)}
            Algorithm: ${this.algorithm}
            Hash Algorithm: ${_unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_3__.HashAlgorithm[this.hashAlgorithm]}
            Nonce: ${_unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(this.nonce)}
            Hash: ${this.hash.toString()}`;
    }
    /**
     * @inheritDoc
     */
    isOwner(publicKey) {
        return Promise.resolve(_unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(publicKey) === _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(this.publicKey));
    }
}
//# sourceMappingURL=DefaultPredicate.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/IPredicate.js":
/*!************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/IPredicate.js ***!
  \************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);

//# sourceMappingURL=IPredicate.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/IPredicateFactory.js":
/*!*******************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/IPredicateFactory.js ***!
  \*******************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);

//# sourceMappingURL=IPredicateFactory.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/MaskedPredicate.js":
/*!*****************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/MaskedPredicate.js ***!
  \*****************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   MaskedPredicate: () => (/* binding */ MaskedPredicate)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborDecoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborDecoder.js");
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/DataHasher.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHasher.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/HashAlgorithm.js */ "./node_modules/@unicitylabs/commons/lib/hash/HashAlgorithm.js");
/* harmony import */ var _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");
/* harmony import */ var _DefaultPredicate_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! ./DefaultPredicate.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/DefaultPredicate.js");
/* harmony import */ var _PredicateType_js__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(/*! ./PredicateType.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/PredicateType.js");







const TYPE = _PredicateType_js__WEBPACK_IMPORTED_MODULE_6__.PredicateType.MASKED;
/**
 * Predicate for masked address transaction.
 */
class MaskedPredicate extends _DefaultPredicate_js__WEBPACK_IMPORTED_MODULE_5__.DefaultPredicate {
    /**
     * @param publicKey     Owner public key
     * @param algorithm     Transaction signing algorithm
     * @param hashAlgorithm Transaction hash algorithm
     * @param nonce         Nonce used in the predicate
     * @param reference     Predicate reference
     * @param hash          Predicate hash
     */
    constructor(publicKey, algorithm, hashAlgorithm, nonce, reference, hash) {
        super(TYPE, publicKey, algorithm, hashAlgorithm, nonce, reference, hash);
    }
    /**
     * Create a new masked predicate for the given owner.
     * @param tokenId token ID.
     * @param tokenType token type.
     * @param signingService Token owner's signing service.
     * @param hashAlgorithm Hash algorithm used to hash transaction.
     * @param nonce Nonce value used during creation, providing uniqueness.
     */
    static create(tokenId, tokenType, signingService, hashAlgorithm, nonce) {
        return MaskedPredicate.createFromPublicKey(tokenId, tokenType, signingService.algorithm, signingService.publicKey, hashAlgorithm, nonce);
    }
    static async createFromPublicKey(tokenId, tokenType, signingAlgorithm, publicKey, hashAlgorithm, nonce) {
        const reference = await MaskedPredicate.calculateReference(tokenType, signingAlgorithm, publicKey, hashAlgorithm, nonce);
        const hash = await MaskedPredicate.calculateHash(reference, tokenId);
        return new MaskedPredicate(publicKey, signingAlgorithm, hashAlgorithm, nonce, reference, hash);
    }
    /**
     * Create a masked predicate from JSON data.
     * @param tokenId Token ID.
     * @param tokenType Token type.
     * @param data JSON data representing the masked predicate.
     */
    static fromJSON(tokenId, tokenType, data) {
        if (!_DefaultPredicate_js__WEBPACK_IMPORTED_MODULE_5__.DefaultPredicate.isJSON(data) || data.type !== TYPE) {
            throw new Error('Invalid masked predicate json.');
        }
        return MaskedPredicate.createFromPublicKey(tokenId, tokenType, data.algorithm, _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.decode(data.publicKey), data.hashAlgorithm, _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.decode(data.nonce));
    }
    static fromCBOR(tokenId, tokenType, bytes) {
        const data = _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__.CborDecoder.readArray(bytes);
        const type = _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__.CborDecoder.readTextString(data[0]);
        if (type !== _PredicateType_js__WEBPACK_IMPORTED_MODULE_6__.PredicateType.MASKED) {
            throw new Error(`Invalid predicate type: expected ${_PredicateType_js__WEBPACK_IMPORTED_MODULE_6__.PredicateType.MASKED}, got ${type}`);
        }
        const hashAlgorithm = _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__.CborDecoder.readTextString(data[3]);
        if (!_unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_3__.HashAlgorithm[hashAlgorithm]) {
            throw new Error(`Invalid hash algorithm: ${hashAlgorithm}`);
        }
        return MaskedPredicate.createFromPublicKey(tokenId, tokenType, _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__.CborDecoder.readTextString(data[2]), _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__.CborDecoder.readByteString(data[1]), hashAlgorithm, _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__.CborDecoder.readByteString(data[4]));
    }
    /**
     * Compute the predicate reference.
     * @param tokenType token type.
     * @param algorithm Signing algorithm.
     * @param publicKey Owner's public key.
     * @param hashAlgorithm Hash algorithm used for signing.
     * @param nonce Nonce providing uniqueness for the predicate.
     */
    static calculateReference(tokenType, algorithm, publicKey, hashAlgorithm, nonce) {
        return new _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_2__.DataHasher(_unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_3__.HashAlgorithm.SHA256)
            .update(_unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeArray([
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeTextString(TYPE),
            tokenType.toCBOR(),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeTextString(algorithm),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeTextString(_unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_3__.HashAlgorithm[hashAlgorithm]),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeByteString(publicKey),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeByteString(nonce),
        ]))
            .digest();
    }
    /**
     * Compute the predicate hash for a specific token and nonce.
     * @param reference Reference hash of the predicate.
     * @param tokenId Token ID.
     * @private
     */
    static calculateHash(reference, tokenId) {
        return new _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_2__.DataHasher(_unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_3__.HashAlgorithm.SHA256)
            .update(_unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeArray([reference.toCBOR(), tokenId.toCBOR()]))
            .digest();
    }
}
//# sourceMappingURL=MaskedPredicate.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/PredicateJsonFactory.js":
/*!**********************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/PredicateJsonFactory.js ***!
  \**********************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   PredicateJsonFactory: () => (/* binding */ PredicateJsonFactory)
/* harmony export */ });
/* harmony import */ var _BurnPredicate_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./BurnPredicate.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/BurnPredicate.js");
/* harmony import */ var _MaskedPredicate_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ./MaskedPredicate.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/MaskedPredicate.js");
/* harmony import */ var _PredicateType_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ./PredicateType.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/PredicateType.js");
/* harmony import */ var _UnmaskedPredicate_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! ./UnmaskedPredicate.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/UnmaskedPredicate.js");




/**
 * Default implementation of {@link IPredicateFactory}.
 */
class PredicateJsonFactory {
    /**
     * @inheritDoc
     */
    create(tokenId, tokenType, data) {
        switch (data.type) {
            case _PredicateType_js__WEBPACK_IMPORTED_MODULE_2__.PredicateType.BURN:
                return _BurnPredicate_js__WEBPACK_IMPORTED_MODULE_0__.BurnPredicate.fromJSON(tokenId, tokenType, data);
            case _PredicateType_js__WEBPACK_IMPORTED_MODULE_2__.PredicateType.MASKED:
                return _MaskedPredicate_js__WEBPACK_IMPORTED_MODULE_1__.MaskedPredicate.fromJSON(tokenId, tokenType, data);
            case _PredicateType_js__WEBPACK_IMPORTED_MODULE_2__.PredicateType.UNMASKED:
                return _UnmaskedPredicate_js__WEBPACK_IMPORTED_MODULE_3__.UnmaskedPredicate.fromJSON(tokenId, tokenType, data);
            default:
                throw new Error(`Unknown predicate type: ${data.type}`);
        }
    }
}
//# sourceMappingURL=PredicateJsonFactory.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/PredicateType.js":
/*!***************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/PredicateType.js ***!
  \***************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   PredicateType: () => (/* binding */ PredicateType)
/* harmony export */ });
/**
 * Enum representing different types of predicates.
 */
var PredicateType;
(function (PredicateType) {
    /** Predicate for masked address */
    PredicateType["MASKED"] = "MASKED";
    /** Predicate for public address */
    PredicateType["UNMASKED"] = "UNMASKED";
    /** Special predicate burning the token */
    PredicateType["BURN"] = "BURN";
})(PredicateType || (PredicateType = {}));
//# sourceMappingURL=PredicateType.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/UnmaskedPredicate.js":
/*!*******************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/UnmaskedPredicate.js ***!
  \*******************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   UnmaskedPredicate: () => (/* binding */ UnmaskedPredicate)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborDecoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborDecoder.js");
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/DataHasher.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHasher.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/HashAlgorithm.js */ "./node_modules/@unicitylabs/commons/lib/hash/HashAlgorithm.js");
/* harmony import */ var _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");
/* harmony import */ var _DefaultPredicate_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! ./DefaultPredicate.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/DefaultPredicate.js");
/* harmony import */ var _PredicateType_js__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(/*! ./PredicateType.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/PredicateType.js");







const TYPE = _PredicateType_js__WEBPACK_IMPORTED_MODULE_6__.PredicateType.UNMASKED;
/**
 * Predicate for public address transaction.
 */
class UnmaskedPredicate extends _DefaultPredicate_js__WEBPACK_IMPORTED_MODULE_5__.DefaultPredicate {
    /**
     * @param publicKey     Owner public key.
     * @param algorithm     Transaction signing algorithm
     * @param hashAlgorithm Transaction hash algorithm
     * @param nonce         Nonce used in the predicate
     * @param reference     Predicate reference
     * @param hash          Predicate hash
     */
    constructor(publicKey, algorithm, hashAlgorithm, nonce, reference, hash) {
        super(TYPE, publicKey, algorithm, hashAlgorithm, nonce, reference, hash);
    }
    /**
     * Create a new unmasked predicate for the given owner.
     * @param tokenId Token ID
     * @param tokenType Token type
     * @param signingService Token owner's signing service
     * @param hashAlgorithm Hash algorithm used to hash transaction
     * @param salt Transaction salt
     */
    static async create(tokenId, tokenType, signingService, hashAlgorithm, salt) {
        const saltHash = await new _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_2__.DataHasher(_unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_3__.HashAlgorithm.SHA256).update(salt).digest();
        const nonce = await signingService.sign(saltHash);
        return UnmaskedPredicate.createFromPublicKey(tokenId, tokenType, signingService.algorithm, signingService.publicKey, hashAlgorithm, nonce.bytes);
    }
    static async createFromPublicKey(tokenId, tokenType, signingAlgorithm, publicKey, hashAlgorithm, nonce) {
        const reference = await UnmaskedPredicate.calculateReference(tokenType, signingAlgorithm, publicKey, hashAlgorithm);
        const hash = await UnmaskedPredicate.calculateHash(reference, tokenId, nonce);
        return new UnmaskedPredicate(publicKey, signingAlgorithm, hashAlgorithm, nonce, reference, hash);
    }
    /**
     * Create a masked predicate from JSON data.
     * @param tokenId Token ID.
     * @param tokenType Token type.
     * @param data JSON data representing the masked predicate.
     */
    static fromJSON(tokenId, tokenType, data) {
        if (!_DefaultPredicate_js__WEBPACK_IMPORTED_MODULE_5__.DefaultPredicate.isJSON(data) || data.type !== TYPE) {
            throw new Error('Invalid unmasked predicate json.');
        }
        return UnmaskedPredicate.createFromPublicKey(tokenId, tokenType, data.algorithm, _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.decode(data.publicKey), data.hashAlgorithm, _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.decode(data.nonce));
    }
    static fromCBOR(tokenId, tokenType, bytes) {
        const data = _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__.CborDecoder.readArray(bytes);
        const type = _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__.CborDecoder.readTextString(data[0]);
        if (type !== _PredicateType_js__WEBPACK_IMPORTED_MODULE_6__.PredicateType.UNMASKED) {
            throw new Error(`Invalid predicate type: expected ${_PredicateType_js__WEBPACK_IMPORTED_MODULE_6__.PredicateType.UNMASKED}, got ${type}`);
        }
        const hashAlgorithm = _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__.CborDecoder.readTextString(data[3]);
        if (!_unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_3__.HashAlgorithm[hashAlgorithm]) {
            throw new Error(`Invalid hash algorithm: ${hashAlgorithm}`);
        }
        return UnmaskedPredicate.createFromPublicKey(tokenId, tokenType, _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__.CborDecoder.readTextString(data[2]), _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__.CborDecoder.readByteString(data[1]), hashAlgorithm, _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__.CborDecoder.readByteString(data[4]));
    }
    /**
     * Calculate the predicate reference.
     * @param tokenType Token type
     * @param algorithm Signing algorithm
     * @param publicKey Owner public key
     * @param hashAlgorithm Hash algorithm used to hash transaction
     */
    static calculateReference(tokenType, algorithm, publicKey, hashAlgorithm) {
        return new _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_2__.DataHasher(_unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_3__.HashAlgorithm.SHA256)
            .update(_unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeArray([
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeTextString(TYPE),
            tokenType.toCBOR(),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeTextString(algorithm),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeTextString(_unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_3__.HashAlgorithm[hashAlgorithm]),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeByteString(publicKey),
        ]))
            .digest();
    }
    /**
     * Calculate the predicate hash.
     * @param reference Reference of the predicate
     * @param tokenId Token ID
     * @param nonce Nonce used in the predicate
     * @private
     */
    static calculateHash(reference, tokenId, nonce) {
        return new _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_2__.DataHasher(_unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_3__.HashAlgorithm.SHA256)
            .update(_unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeArray([reference.toCBOR(), tokenId.toCBOR(), _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeByteString(nonce)]))
            .digest();
    }
}
//# sourceMappingURL=UnmaskedPredicate.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/serializer/token/TokenJsonDeserializer.js":
/*!******************************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/serializer/token/TokenJsonDeserializer.js ***!
  \******************************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   TokenJsonDeserializer: () => (/* binding */ TokenJsonDeserializer)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");
/* harmony import */ var _token_Token_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ../../token/Token.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/Token.js");
/* harmony import */ var _token_TokenState_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ../../token/TokenState.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/TokenState.js");
/* harmony import */ var _transaction_MintTransactionJsonDeserializer_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! ../transaction/MintTransactionJsonDeserializer.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/serializer/transaction/MintTransactionJsonDeserializer.js");
/* harmony import */ var _transaction_TransactionJsonDeserializer_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! ../transaction/TransactionJsonDeserializer.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/serializer/transaction/TransactionJsonDeserializer.js");





class TokenJsonDeserializer {
    predicateFactory;
    mintTransactionDeserializer;
    transactionDeserializer;
    constructor(predicateFactory) {
        this.predicateFactory = predicateFactory;
        this.mintTransactionDeserializer = new _transaction_MintTransactionJsonDeserializer_js__WEBPACK_IMPORTED_MODULE_3__.MintTransactionJsonDeserializer(this);
        this.transactionDeserializer = new _transaction_TransactionJsonDeserializer_js__WEBPACK_IMPORTED_MODULE_4__.TransactionJsonDeserializer(predicateFactory);
    }
    async deserialize(data) {
        const tokenVersion = data.version;
        if (tokenVersion !== _token_Token_js__WEBPACK_IMPORTED_MODULE_1__.TOKEN_VERSION) {
            throw new Error(`Cannot parse token. Version mismatch: ${tokenVersion} !== ${_token_Token_js__WEBPACK_IMPORTED_MODULE_1__.TOKEN_VERSION}`);
        }
        const mintTransaction = await this.mintTransactionDeserializer.deserialize(data.genesis);
        const transactions = [];
        for (const transaction of data.transactions) {
            transactions.push(await this.transactionDeserializer.deserialize(mintTransaction.data.tokenId, mintTransaction.data.tokenType, transaction));
        }
        // TODO: Add nametag tokens
        return new _token_Token_js__WEBPACK_IMPORTED_MODULE_1__.Token(await _token_TokenState_js__WEBPACK_IMPORTED_MODULE_2__.TokenState.create(await this.predicateFactory.create(mintTransaction.data.tokenId, mintTransaction.data.tokenType, data.state.unlockPredicate), data.state.data ? _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_0__.HexConverter.decode(data.state.data) : null), mintTransaction, transactions, [], tokenVersion);
    }
}
//# sourceMappingURL=TokenJsonDeserializer.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/serializer/transaction/MintTransactionJsonDeserializer.js":
/*!**********************************************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/serializer/transaction/MintTransactionJsonDeserializer.js ***!
  \**********************************************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   MintTransactionJsonDeserializer: () => (/* binding */ MintTransactionJsonDeserializer)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_api_InclusionProof_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/InclusionProof.js */ "./node_modules/@unicitylabs/commons/lib/api/InclusionProof.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_DataHash_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/DataHash.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHash.js");
/* harmony import */ var _unicitylabs_commons_lib_smst_MerkleSumTreePath_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! @unicitylabs/commons/lib/smst/MerkleSumTreePath.js */ "./node_modules/@unicitylabs/commons/lib/smst/MerkleSumTreePath.js");
/* harmony import */ var _unicitylabs_commons_lib_smt_MerkleTreePath_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! @unicitylabs/commons/lib/smt/MerkleTreePath.js */ "./node_modules/@unicitylabs/commons/lib/smt/MerkleTreePath.js");
/* harmony import */ var _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");
/* harmony import */ var _token_fungible_SplitMintReason_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! ../../token/fungible/SplitMintReason.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/fungible/SplitMintReason.js");
/* harmony import */ var _token_fungible_SplitMintReasonProof_js__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(/*! ../../token/fungible/SplitMintReasonProof.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/fungible/SplitMintReasonProof.js");
/* harmony import */ var _token_fungible_TokenCoinData_js__WEBPACK_IMPORTED_MODULE_7__ = __webpack_require__(/*! ../../token/fungible/TokenCoinData.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/fungible/TokenCoinData.js");
/* harmony import */ var _token_TokenId_js__WEBPACK_IMPORTED_MODULE_8__ = __webpack_require__(/*! ../../token/TokenId.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/TokenId.js");
/* harmony import */ var _token_TokenType_js__WEBPACK_IMPORTED_MODULE_9__ = __webpack_require__(/*! ../../token/TokenType.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/TokenType.js");
/* harmony import */ var _transaction_MintReasonType_js__WEBPACK_IMPORTED_MODULE_10__ = __webpack_require__(/*! ../../transaction/MintReasonType.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/MintReasonType.js");
/* harmony import */ var _transaction_MintTransactionData_js__WEBPACK_IMPORTED_MODULE_11__ = __webpack_require__(/*! ../../transaction/MintTransactionData.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/MintTransactionData.js");
/* harmony import */ var _transaction_Transaction_js__WEBPACK_IMPORTED_MODULE_12__ = __webpack_require__(/*! ../../transaction/Transaction.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/Transaction.js");













class MintTransactionJsonDeserializer {
    tokenDeserializer;
    constructor(tokenDeserializer) {
        this.tokenDeserializer = tokenDeserializer;
    }
    async deserialize({ data, inclusionProof, }) {
        return new _transaction_Transaction_js__WEBPACK_IMPORTED_MODULE_12__.Transaction(await _transaction_MintTransactionData_js__WEBPACK_IMPORTED_MODULE_11__.MintTransactionData.create(_token_TokenId_js__WEBPACK_IMPORTED_MODULE_8__.TokenId.create(_unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.decode(data.tokenId)), _token_TokenType_js__WEBPACK_IMPORTED_MODULE_9__.TokenType.create(_unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.decode(data.tokenType)), _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.decode(data.tokenData), data.coins ? _token_fungible_TokenCoinData_js__WEBPACK_IMPORTED_MODULE_7__.TokenCoinData.fromJSON(data.coins) : null, data.recipient, _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.decode(data.salt), data.dataHash ? _unicitylabs_commons_lib_hash_DataHash_js__WEBPACK_IMPORTED_MODULE_1__.DataHash.fromJSON(data.dataHash) : null, data.reason ? await this.createMintReason(data.reason) : null), _unicitylabs_commons_lib_api_InclusionProof_js__WEBPACK_IMPORTED_MODULE_0__.InclusionProof.fromJSON(inclusionProof));
    }
    createMintReason(data) {
        switch (data.type) {
            case _transaction_MintReasonType_js__WEBPACK_IMPORTED_MODULE_10__.MintReasonType.TOKEN_SPLIT:
                return this.createSplitMintReason(data);
            default:
                throw new Error(`Unsupported mint reason type: ${data.type}`);
        }
    }
    async createSplitMintReason(data) {
        const proofs = new Map();
        for (const [coinId, proof] of data.proofs) {
            proofs.set(BigInt(coinId), new _token_fungible_SplitMintReasonProof_js__WEBPACK_IMPORTED_MODULE_6__.SplitMintReasonProof(_unicitylabs_commons_lib_smt_MerkleTreePath_js__WEBPACK_IMPORTED_MODULE_3__.MerkleTreePath.fromJSON(proof.aggregationPath), _unicitylabs_commons_lib_smst_MerkleSumTreePath_js__WEBPACK_IMPORTED_MODULE_2__.MerkleSumTreePath.fromJSON(proof.coinTreePath)));
        }
        return new _token_fungible_SplitMintReason_js__WEBPACK_IMPORTED_MODULE_5__.SplitMintReason(await this.tokenDeserializer.deserialize(data.token), proofs);
    }
}
//# sourceMappingURL=MintTransactionJsonDeserializer.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/serializer/transaction/TransactionJsonDeserializer.js":
/*!******************************************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/serializer/transaction/TransactionJsonDeserializer.js ***!
  \******************************************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   TransactionJsonDeserializer: () => (/* binding */ TransactionJsonDeserializer)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_api_InclusionProof_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/InclusionProof.js */ "./node_modules/@unicitylabs/commons/lib/api/InclusionProof.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_DataHash_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/DataHash.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHash.js");
/* harmony import */ var _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");
/* harmony import */ var _token_TokenState_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! ../../token/TokenState.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/TokenState.js");
/* harmony import */ var _transaction_Transaction_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! ../../transaction/Transaction.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/Transaction.js");
/* harmony import */ var _transaction_TransactionData_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! ../../transaction/TransactionData.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/TransactionData.js");






class TransactionJsonDeserializer {
    predicateFactory;
    constructor(predicateFactory) {
        this.predicateFactory = predicateFactory;
    }
    async deserialize(tokenId, tokenType, { data, inclusionProof }) {
        return new _transaction_Transaction_js__WEBPACK_IMPORTED_MODULE_4__.Transaction(await _transaction_TransactionData_js__WEBPACK_IMPORTED_MODULE_5__.TransactionData.create(await _token_TokenState_js__WEBPACK_IMPORTED_MODULE_3__.TokenState.create(await this.predicateFactory.create(tokenId, tokenType, data.sourceState.unlockPredicate), data.sourceState.data ? _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_2__.HexConverter.decode(data.sourceState.data) : null), data.recipient, _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_2__.HexConverter.decode(data.salt), data.dataHash ? _unicitylabs_commons_lib_hash_DataHash_js__WEBPACK_IMPORTED_MODULE_1__.DataHash.fromJSON(data.dataHash) : null, data.message ? _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_2__.HexConverter.decode(data.message) : null, []), _unicitylabs_commons_lib_api_InclusionProof_js__WEBPACK_IMPORTED_MODULE_0__.InclusionProof.fromJSON(inclusionProof));
    }
}
//# sourceMappingURL=TransactionJsonDeserializer.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/NameTagToken.js":
/*!**********************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/token/NameTagToken.js ***!
  \**********************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);

//# sourceMappingURL=NameTagToken.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/NameTagTokenData.js":
/*!**************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/token/NameTagTokenData.js ***!
  \**************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   NameTagTokenData: () => (/* binding */ NameTagTokenData)
/* harmony export */ });
/**
 * Placeholder data type for name tag tokens.
 */
class NameTagTokenData {
    /**
     * Decode a name tag payload. Currently returns an empty instance.
     */
    static decode() {
        return Promise.resolve(new NameTagTokenData());
    }
    /** @throws Always throws - not implemented. */
    toJSON() {
        throw new Error('toJSON method is not implemented.');
    }
    /** @throws Always throws - not implemented. */
    toCBOR() {
        throw new Error('toCBOR method is not implemented.');
    }
}
//# sourceMappingURL=NameTagTokenData.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/Token.js":
/*!***************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/token/Token.js ***!
  \***************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   TOKEN_VERSION: () => (/* binding */ TOKEN_VERSION),
/* harmony export */   Token: () => (/* binding */ Token)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _unicitylabs_commons_lib_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/StringUtils.js */ "./node_modules/@unicitylabs/commons/lib/util/StringUtils.js");


/** Current serialization version for tokens. */
const TOKEN_VERSION = '2.0';
/**
 * In-memory representation of a token including its transaction history.
 */
class Token {
    state;
    genesis;
    _transactions;
    _nametagTokens;
    version;
    /**
     * Create a new token instance.
     * @param state Current state of the token including state data and unlock predicate
     * @param genesis Mint transaction that created this token
     * @param _transactions History of transactions
     * @param _nametagTokens List of nametag tokens associated with this token
     * @param version Serialization version of the token, defaults to {@link TOKEN_VERSION}
     */
    constructor(state, genesis, _transactions = [], _nametagTokens = [], version = TOKEN_VERSION) {
        this.state = state;
        this.genesis = genesis;
        this._transactions = _transactions;
        this._nametagTokens = _nametagTokens;
        this.version = version;
        this._nametagTokens = _nametagTokens.slice();
        this._transactions = _transactions.slice();
    }
    get id() {
        return this.genesis.data.tokenId;
    }
    get type() {
        return this.genesis.data.tokenType;
    }
    /**
     * Token immutable data.
     */
    get data() {
        return this.genesis.data.tokenData;
    }
    get coins() {
        return this.genesis.data.coinData;
    }
    /** Nametag tokens associated with this token. */
    get nametagTokens() {
        return this._nametagTokens.slice();
    }
    /** History of all transactions starting with the mint transaction. */
    get transactions() {
        return this._transactions.slice();
    }
    /** Serialize this token to JSON. */
    toJSON() {
        return {
            genesis: this.genesis.toJSON(),
            nametagTokens: [],
            state: this.state.toJSON(),
            transactions: this.transactions.map((transaction) => transaction.toJSON()),
            version: this.version,
        };
    }
    /** Serialize this token to CBOR. */
    toCBOR() {
        return _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeArray([
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeTextString(this.version),
            this.genesis.toCBOR(),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeArray(this.transactions.map((transaction) => transaction.toCBOR())),
            this.state.toCBOR(),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeArray(this.nametagTokens.map((token) => token.toCBOR())),
        ]);
    }
    /** Convert instance to readable string */
    toString() {
        return (0,_unicitylabs_commons_lib_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_1__.dedent) `
        Token[${this.version}]:
          Id: ${this.id.toString()}
          Type: ${this.type.toString()}
          Data: 
            ${this.data.toString()}
          Coins:
            ${this.coins?.toString() ?? null}
          State:
            ${this.state.toString()}
          Transactions: [
            ${this.transactions.map((transition) => transition.toString()).join('\n')}
          ]
          Nametag Tokens: [ 
            ${this.nametagTokens.map((token) => token.toString()).join('\n')}
          ]
      `;
    }
}
//# sourceMappingURL=Token.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/TokenFactory.js":
/*!**********************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/token/TokenFactory.js ***!
  \**********************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   TokenFactory: () => (/* binding */ TokenFactory)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_api_InclusionProof_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/InclusionProof.js */ "./node_modules/@unicitylabs/commons/lib/api/InclusionProof.js");
/* harmony import */ var _unicitylabs_commons_lib_api_RequestId_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/RequestId.js */ "./node_modules/@unicitylabs/commons/lib/api/RequestId.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_DataHash_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/DataHash.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHash.js");
/* harmony import */ var _unicitylabs_commons_lib_signing_SigningService_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! @unicitylabs/commons/lib/signing/SigningService.js */ "./node_modules/@unicitylabs/commons/lib/signing/SigningService.js");
/* harmony import */ var _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");
/* harmony import */ var _address_DirectAddress_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! ../address/DirectAddress.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/address/DirectAddress.js");
/* harmony import */ var _StateTransitionClient_js__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(/*! ../StateTransitionClient.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/StateTransitionClient.js");
/* harmony import */ var _predicate_PredicateType_js__WEBPACK_IMPORTED_MODULE_7__ = __webpack_require__(/*! ../predicate/PredicateType.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/PredicateType.js");
/* harmony import */ var _fungible_SplitMintReason_js__WEBPACK_IMPORTED_MODULE_8__ = __webpack_require__(/*! ./fungible/SplitMintReason.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/fungible/SplitMintReason.js");









/**
 * Utility for constructing tokens from their serialized form.
 */
class TokenFactory {
    deserializer;
    /**
     * @param deserializer token deserializer to use for parsing tokens from CBOR or JSON
     */
    constructor(deserializer) {
        this.deserializer = deserializer;
    }
    /**
     * Deserialize a token from JSON.
     *
     * @param data       Token JSON representation
     */
    async create(data) {
        const token = await this.deserializer.deserialize(data);
        if (!(await this.verifyMintTransaction(token.genesis))) {
            throw new Error('Mint transaction verification failed.');
        }
        let previousTransaction = token.genesis;
        for (const transaction of token.transactions) {
            // TODO: Move address processing to a separate method
            const expectedRecipient = await _address_DirectAddress_js__WEBPACK_IMPORTED_MODULE_5__.DirectAddress.create(transaction.data.sourceState.unlockPredicate.reference);
            if (expectedRecipient.toJSON() !== previousTransaction.data.recipient) {
                throw new Error('Recipient address mismatch');
            }
            if (!(await previousTransaction.containsData(transaction.data.sourceState.data))) {
                throw new Error('State data is not part of transaction.');
            }
            if (!(await transaction.data.sourceState.unlockPredicate.verify(transaction))) {
                throw new Error('Predicate verification failed');
            }
            previousTransaction = transaction;
        }
        if (!(await previousTransaction.containsData(token.state.data))) {
            throw new Error('State data is not part of transaction.');
        }
        const expectedRecipient = await _address_DirectAddress_js__WEBPACK_IMPORTED_MODULE_5__.DirectAddress.create(token.state.unlockPredicate.reference);
        if (expectedRecipient.toJSON() !== previousTransaction.data.recipient) {
            throw new Error('Recipient address mismatch');
        }
        return token;
    }
    /**
     * Verify a mint transaction integrity and validate against public key.
     * @param transaction Mint transaction
     * @private
     */
    async verifyMintTransaction(transaction) {
        if (!transaction.inclusionProof.authenticator || !transaction.inclusionProof.transactionHash) {
            return false;
        }
        const signingService = await _unicitylabs_commons_lib_signing_SigningService_js__WEBPACK_IMPORTED_MODULE_3__.SigningService.createFromSecret(_StateTransitionClient_js__WEBPACK_IMPORTED_MODULE_6__.MINTER_SECRET, transaction.data.tokenId.bytes);
        if (_unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(transaction.inclusionProof.authenticator.publicKey) !==
            _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(signingService.publicKey) ||
            !transaction.inclusionProof.authenticator.stateHash.equals(transaction.data.sourceState.hash)) {
            return false; // input mismatch
        }
        // Verify if transaction data is valid.
        if (!(await transaction.inclusionProof.authenticator.verify(transaction.data.hash))) {
            return false;
        }
        const reason = transaction.data.reason;
        if (reason instanceof _fungible_SplitMintReason_js__WEBPACK_IMPORTED_MODULE_8__.SplitMintReason) {
            if (transaction.data.coinData == null) {
                return false;
            }
            if (reason.token.state.unlockPredicate.type != _predicate_PredicateType_js__WEBPACK_IMPORTED_MODULE_7__.PredicateType.BURN) {
                return false;
            }
            if (transaction.data.coinData.size !== reason.proofs.size) {
                return false;
            }
            for (const [coinId, proof] of reason.proofs) {
                const aggregationPathResult = await proof.aggregationPath.verify(coinId);
                if (!aggregationPathResult.result) {
                    return false;
                }
                const coinPathResult = await proof.coinTreePath.verify(transaction.data.tokenId.toBigInt());
                if (!coinPathResult.result) {
                    return false;
                }
                const aggregationPathLeaf = proof.aggregationPath.steps.at(0)?.branch?.value;
                if (!aggregationPathLeaf || !proof.coinTreePath.root.equals(_unicitylabs_commons_lib_hash_DataHash_js__WEBPACK_IMPORTED_MODULE_2__.DataHash.fromImprint(aggregationPathLeaf))) {
                    return false;
                }
                const sumPathLeaf = proof.coinTreePath.steps.at(0)?.branch?.sum;
                if (transaction.data.coinData?.getByKey(coinId) !== sumPathLeaf) {
                    return false;
                }
                const predicate = reason.token.state.unlockPredicate;
                if (!proof.aggregationPath.root.equals(predicate.reason)) {
                    return false;
                }
            }
        }
        // Verify inclusion proof path.
        const requestId = await _unicitylabs_commons_lib_api_RequestId_js__WEBPACK_IMPORTED_MODULE_1__.RequestId.create(signingService.publicKey, transaction.data.sourceState.hash);
        const status = await transaction.inclusionProof.verify(requestId.toBigInt());
        return status === _unicitylabs_commons_lib_api_InclusionProof_js__WEBPACK_IMPORTED_MODULE_0__.InclusionProofVerificationStatus.OK;
    }
}
//# sourceMappingURL=TokenFactory.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/TokenId.js":
/*!*****************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/token/TokenId.js ***!
  \*****************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   TokenId: () => (/* binding */ TokenId)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");


/**
 * Globally unique identifier of a token.
 */
class TokenId {
    _bytes;
    /**
     * @param _bytes Byte representation of the identifier
     */
    constructor(_bytes) {
        this._bytes = _bytes;
        this._bytes = new Uint8Array(_bytes);
    }
    get bytes() {
        return new Uint8Array(this._bytes);
    }
    /** Factory method to wrap a raw identifier. */
    static create(id) {
        return new TokenId(id);
    }
    /** Encode as a hex string for JSON. */
    toJSON() {
        return _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_1__.HexConverter.encode(this._bytes);
    }
    /** CBOR serialisation. */
    toCBOR() {
        return _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeByteString(this._bytes);
    }
    /** Convert instance to readable string */
    toString() {
        return `TokenId[${_unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_1__.HexConverter.encode(this._bytes)}]`;
    }
    /**
     * Converts the TokenId to a BigInt representation.
     */
    toBigInt() {
        return BigInt(`0x01${_unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_1__.HexConverter.encode(this.toCBOR())}`);
    }
}
//# sourceMappingURL=TokenId.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/TokenState.js":
/*!********************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/token/TokenState.js ***!
  \********************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   TokenState: () => (/* binding */ TokenState)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/DataHasher.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHasher.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/HashAlgorithm.js */ "./node_modules/@unicitylabs/commons/lib/hash/HashAlgorithm.js");
/* harmony import */ var _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");
/* harmony import */ var _unicitylabs_commons_lib_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/StringUtils.js */ "./node_modules/@unicitylabs/commons/lib/util/StringUtils.js");





/**
 * Represents a snapshot of token ownership and associated data.
 */
class TokenState {
    unlockPredicate;
    _data;
    hash;
    /**
     * @param unlockPredicate Predicate controlling future transfers
     * @param _data           Optional encrypted state data
     * @param hash            Hash of predicate and data
     */
    constructor(unlockPredicate, _data, hash) {
        this.unlockPredicate = unlockPredicate;
        this._data = _data;
        this.hash = hash;
        this._data = _data ? new Uint8Array(_data) : null;
    }
    /** Copy of the stored state data. */
    get data() {
        return this._data ? new Uint8Array(this._data) : null;
    }
    /** Hash algorithm used for the state hash. */
    get hashAlgorithm() {
        return this.hash.algorithm;
    }
    /**
     * Compute a new token state from predicate and optional data.
     */
    static async create(unlockPredicate, data) {
        return new TokenState(unlockPredicate, data, await new _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_1__.DataHasher(_unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_2__.HashAlgorithm.SHA256)
            .update(_unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeArray([
            unlockPredicate.hash.toCBOR(),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeOptional(data, _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeByteString),
        ]))
            .digest());
    }
    /** Serialize the state to JSON. */
    toJSON() {
        return {
            data: this._data ? _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_3__.HexConverter.encode(this._data) : null,
            unlockPredicate: this.unlockPredicate.toJSON(),
        };
    }
    /** Encode the state as CBOR. */
    toCBOR() {
        return _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeArray([
            this.unlockPredicate.toCBOR(),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeOptional(this._data, _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeByteString),
        ]);
    }
    /** Convert instance to readable string */
    toString() {
        return (0,_unicitylabs_commons_lib_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_4__.dedent) `
        TokenState:
          ${this.unlockPredicate.toString()}
          Data: ${this._data ? _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_3__.HexConverter.encode(this._data) : null}
          Hash: ${this.hash.toString()}`;
    }
}
//# sourceMappingURL=TokenState.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/TokenType.js":
/*!*******************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/token/TokenType.js ***!
  \*******************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   TokenType: () => (/* binding */ TokenType)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");


/** Unique identifier describing the type/category of a token. */
class TokenType {
    _bytes;
    /**
     * @param _bytes Byte representation of the token type
     */
    constructor(_bytes) {
        this._bytes = _bytes;
        this._bytes = new Uint8Array(_bytes);
    }
    get bytes() {
        return new Uint8Array(this._bytes);
    }
    /** Create an instance from raw bytes. */
    static create(id) {
        return new TokenType(id);
    }
    /** Hex representation for JSON serialization. */
    toJSON() {
        return _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_1__.HexConverter.encode(this._bytes);
    }
    /** CBOR serialization. */
    toCBOR() {
        return _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeByteString(this._bytes);
    }
    /** Convert instance to readable string */
    toString() {
        return `TokenType[${_unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_1__.HexConverter.encode(this._bytes)}]`;
    }
}
//# sourceMappingURL=TokenType.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/fungible/CoinId.js":
/*!*************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/token/fungible/CoinId.js ***!
  \*************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   CoinId: () => (/* binding */ CoinId)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborDecoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborDecoder.js");
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");



/** Identifier for a fungible coin type. */
class CoinId {
    data;
    /**
     * @param data Raw byte representation
     */
    constructor(data) {
        this.data = data;
        this.data = new Uint8Array(data);
    }
    /**
     * Creates a new CoinId from raw bytes.
     * @param data Raw byte representation
     */
    static fromJSON(data) {
        return new CoinId(_unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_2__.HexConverter.decode(data));
    }
    /**
     * Creates a CoinId from a byte array encoded in CBOR.
     * @param data
     */
    static fromCBOR(data) {
        return new CoinId(_unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__.CborDecoder.readByteString(data));
    }
    /**
     * Creates a CoinId from a bigint.
     * @param value bigint represantation of coin id
     */
    static fromBigInt(value) {
        return CoinId.fromCBOR(_unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_2__.HexConverter.decode(value.toString(16).slice(1)));
    }
    /** Hex string representation. */
    toJSON() {
        return _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_2__.HexConverter.encode(this.data);
    }
    /** CBOR serialization. */
    toCBOR() {
        return _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeByteString(this.data);
    }
    /**
     * Converts the CoinId to a BigInt representation.
     */
    toBigInt() {
        return BigInt(`0x01${_unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_2__.HexConverter.encode(this.toCBOR())}`);
    }
}
//# sourceMappingURL=CoinId.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/fungible/SplitMintReason.js":
/*!**********************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/token/fungible/SplitMintReason.js ***!
  \**********************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   SplitMintReason: () => (/* binding */ SplitMintReason)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _unicitylabs_commons_lib_util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/BigintConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/BigintConverter.js");
/* harmony import */ var _transaction_MintReasonType_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ../../transaction/MintReasonType.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/MintReasonType.js");



class SplitMintReason {
    token;
    _proofs;
    constructor(token, _proofs) {
        this.token = token;
        this._proofs = _proofs;
        this._proofs = new Map(_proofs);
    }
    get proofs() {
        return new Map(this._proofs);
    }
    toCBOR() {
        return _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeArray([
            this.token.toCBOR(),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeArray(Array.from(this._proofs.entries()).map(([coinId, proof]) => _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeArray([_unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeByteString(_unicitylabs_commons_lib_util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_1__.BigintConverter.encode(coinId)), proof.toCBOR()]))),
        ]);
    }
    toJSON() {
        return {
            proofs: Array.from(this._proofs).map(([coinId, proof]) => [coinId.toString(), proof.toJSON()]),
            token: this.token.toJSON(),
            type: _transaction_MintReasonType_js__WEBPACK_IMPORTED_MODULE_2__.MintReasonType.TOKEN_SPLIT,
        };
    }
}
//# sourceMappingURL=SplitMintReason.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/fungible/SplitMintReasonProof.js":
/*!***************************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/token/fungible/SplitMintReasonProof.js ***!
  \***************************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   SplitMintReasonProof: () => (/* binding */ SplitMintReasonProof)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");

class SplitMintReasonProof {
    aggregationPath;
    coinTreePath;
    constructor(aggregationPath, coinTreePath) {
        this.aggregationPath = aggregationPath;
        this.coinTreePath = coinTreePath;
    }
    toJSON() {
        return {
            aggregationPath: this.aggregationPath.toJSON(),
            coinTreePath: this.coinTreePath.toJSON(),
        };
    }
    toCBOR() {
        return _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeArray([this.aggregationPath.toCBOR(), this.coinTreePath.toCBOR()]);
    }
}
//# sourceMappingURL=SplitMintReasonProof.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/fungible/TokenCoinData.js":
/*!********************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/token/fungible/TokenCoinData.js ***!
  \********************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   TokenCoinData: () => (/* binding */ TokenCoinData)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborDecoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborDecoder.js");
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _unicitylabs_commons_lib_util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/BigintConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/BigintConverter.js");
/* harmony import */ var _unicitylabs_commons_lib_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/StringUtils.js */ "./node_modules/@unicitylabs/commons/lib/util/StringUtils.js");
/* harmony import */ var _CoinId_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! ./CoinId.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/fungible/CoinId.js");





/**
 * Container for fungible coin balances attached to a token.
 */
class TokenCoinData {
    _coins;
    /**
     * @param coins Array of coin id and balance pairs
     */
    constructor(coins) {
        this._coins = new Map(coins);
    }
    /** Get total number of different coins */
    get size() {
        return this._coins.size;
    }
    get coins() {
        return new Map(Array.from(this._coins.entries()).map(([key, value]) => [_CoinId_js__WEBPACK_IMPORTED_MODULE_4__.CoinId.fromBigInt(key), value]));
    }
    static create(coins) {
        return new TokenCoinData(coins.map(([key, value]) => [key.toBigInt(), value]));
    }
    /** Create a coin data object from CBOR. */
    static fromCBOR(data) {
        const coins = [];
        const entries = _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__.CborDecoder.readArray(data);
        for (const item of entries) {
            const [key, value] = _unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__.CborDecoder.readArray(item);
            coins.push([
                _unicitylabs_commons_lib_util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_2__.BigintConverter.decode(_unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__.CborDecoder.readByteString(key)),
                _unicitylabs_commons_lib_util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_2__.BigintConverter.decode(_unicitylabs_commons_lib_cbor_CborDecoder_js__WEBPACK_IMPORTED_MODULE_0__.CborDecoder.readByteString(value)),
            ]);
        }
        return new TokenCoinData(coins);
    }
    /** Parse from a JSON representation. */
    static fromJSON(data) {
        if (!Array.isArray(data)) {
            throw new Error('Invalid coin data JSON format');
        }
        const coins = [];
        // Helper function to safely parse values that might have been corrupted by JSON.stringify()
        const parseValue = (v) => {
            if (typeof v === 'bigint')
                return v;
            if (typeof v === 'string' || typeof v === 'number')
                return BigInt(v);
            if (v === null) {
                throw new Error(`Cannot convert null to BigInt. This indicates a JSON serialization issue with BigInt values.`);
            }
            if (typeof v === 'object') {
                throw new Error(`Cannot convert object to BigInt. This indicates a JSON serialization issue with BigInt values. Received: ${JSON.stringify(v)}`);
            }
            throw new Error(`Unsupported type for BigInt conversion: ${typeof v}. Expected string, number, or bigint.`);
        };
        for (const [key, value] of data) {
            coins.push([parseValue(key), parseValue(value)]);
        }
        return new TokenCoinData(coins);
    }
    /** Get the balance of a specific coin. */
    get(coinId) {
        return this._coins.get(coinId.toBigInt());
    }
    /** Get the balance of a coin by its internal map key. */
    getByKey(coinId) {
        return this._coins.get(coinId);
    }
    /** @inheritDoc */
    toCBOR() {
        return _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeArray(Array.from(this._coins.entries()).map(([key, value]) => _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeArray([
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeByteString(_unicitylabs_commons_lib_util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_2__.BigintConverter.encode(key)),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeByteString(_unicitylabs_commons_lib_util_BigintConverter_js__WEBPACK_IMPORTED_MODULE_2__.BigintConverter.encode(value)),
        ])));
    }
    /** @inheritDoc */
    toJSON() {
        return Array.from(this._coins.entries()).map(([key, value]) => [key.toString(), value.toString()]);
    }
    /** Convert instance to readable string */
    toString() {
        return (0,_unicitylabs_commons_lib_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_3__.dedent) `
      FungibleTokenData
        ${Array.from(this._coins.entries())
            .map(([key, value]) => `${key}: ${value}`)
            .join('\n')}`;
    }
}
//# sourceMappingURL=TokenCoinData.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/Commitment.js":
/*!**************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/Commitment.js ***!
  \**************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   Commitment: () => (/* binding */ Commitment)
/* harmony export */ });
/**
 * Result returned when submitting a transaction to the aggregator.
 */
class Commitment {
    requestId;
    transactionData;
    authenticator;
    _brand = 'Commitment';
    /**
     * @param requestId       Request identifier used for submission
     * @param transactionData Submitted transaction data
     * @param authenticator   Signature over the payload
     */
    constructor(requestId, transactionData, authenticator) {
        this.requestId = requestId;
        this.transactionData = transactionData;
        this.authenticator = authenticator;
    }
}
//# sourceMappingURL=Commitment.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/MintReasonType.js":
/*!******************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/MintReasonType.js ***!
  \******************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   MintReasonType: () => (/* binding */ MintReasonType)
/* harmony export */ });
var MintReasonType;
(function (MintReasonType) {
    MintReasonType["TOKEN_SPLIT"] = "TOKEN_SPLIT";
})(MintReasonType || (MintReasonType = {}));
//# sourceMappingURL=MintReasonType.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/MintTransactionData.js":
/*!***********************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/MintTransactionData.js ***!
  \***********************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   MintTransactionData: () => (/* binding */ MintTransactionData)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_api_RequestId_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/RequestId.js */ "./node_modules/@unicitylabs/commons/lib/api/RequestId.js");
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/DataHasher.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHasher.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/HashAlgorithm.js */ "./node_modules/@unicitylabs/commons/lib/hash/HashAlgorithm.js");
/* harmony import */ var _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");
/* harmony import */ var _unicitylabs_commons_lib_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/StringUtils.js */ "./node_modules/@unicitylabs/commons/lib/util/StringUtils.js");






// TOKENID string SHA-256 hash
/**
 * Constant suffix used when deriving the mint initial state.
 */
const MINT_SUFFIX = _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.decode('9e82002c144d7c5796c50f6db50a0c7bbd7f717ae3af6c6c71a3e9eba3022730');
/**
 * Data object describing a token mint operation.
 */
class MintTransactionData {
    hash;
    tokenId;
    tokenType;
    _tokenData;
    coinData;
    sourceState;
    recipient;
    _salt;
    dataHash;
    reason;
    /**
     * @param hash        Hash of the encoded transaction
     * @param tokenId     Token identifier
     * @param tokenType   Token type identifier
     * @param _tokenData  Immutable token data used for the mint
     * @param coinData    Fungible coin data, or null if none
     * @param sourceState Pseudo input state used for the mint
     * @param recipient   Address of the first owner
     * @param _salt       Random salt used to derive predicates
     * @param dataHash    Optional metadata hash
     * @param reason      Optional reason object
     */
    constructor(hash, tokenId, tokenType, _tokenData, coinData, sourceState, recipient, _salt, dataHash, reason) {
        this.hash = hash;
        this.tokenId = tokenId;
        this.tokenType = tokenType;
        this._tokenData = _tokenData;
        this.coinData = coinData;
        this.sourceState = sourceState;
        this.recipient = recipient;
        this._salt = _salt;
        this.dataHash = dataHash;
        this.reason = reason;
        this._tokenData = new Uint8Array(_tokenData);
        this._salt = new Uint8Array(_salt);
    }
    /** Immutable token data used for the mint. */
    get tokenData() {
        return new Uint8Array(this._tokenData);
    }
    /** Salt used during predicate creation. */
    get salt() {
        return new Uint8Array(this._salt);
    }
    /** Hash algorithm of the transaction hash. */
    get hashAlgorithm() {
        return this.hash.algorithm;
    }
    /**
     * Create a new mint transaction data object.
     * @param tokenId Token identifier
     * @param tokenType Token type identifier
     * @param tokenData Token data object
     * @param coinData Fungible coin data, or null if none
     * @param recipient Address of the first token owner
     * @param salt User selected salt
     * @param dataHash Hash pointing to next state data
     * @param reason Reason object attached to the mint
     */
    static async create(tokenId, tokenType, tokenData, coinData, recipient, salt, dataHash, reason) {
        const sourceState = await _unicitylabs_commons_lib_api_RequestId_js__WEBPACK_IMPORTED_MODULE_0__.RequestId.createFromImprint(tokenId.bytes, MINT_SUFFIX);
        const tokenDataHash = await new _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_2__.DataHasher(_unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_3__.HashAlgorithm.SHA256).update(tokenData).digest();
        return new MintTransactionData(await new _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_2__.DataHasher(_unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_3__.HashAlgorithm.SHA256)
            .update(_unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeArray([
            tokenId.toCBOR(),
            tokenType.toCBOR(),
            tokenDataHash.toCBOR(),
            dataHash?.toCBOR() ?? _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeNull(),
            coinData?.toCBOR() ?? _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeNull(),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeTextString(recipient),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeByteString(salt),
            reason?.toCBOR() ?? _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeNull(),
        ]))
            .digest(), tokenId, tokenType, tokenData, coinData, sourceState, recipient, salt, dataHash, reason);
    }
    /** Serialize this object to JSON object. */
    toJSON() {
        return {
            coins: this.coinData?.toJSON() ?? null,
            dataHash: this.dataHash?.toJSON() ?? null,
            reason: this.reason?.toJSON() ?? null,
            recipient: this.recipient,
            salt: _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(this._salt),
            tokenData: _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(this._tokenData),
            tokenId: this.tokenId.toJSON(),
            tokenType: this.tokenType.toJSON(),
        };
    }
    /** Serialize this object to CBOR. */
    toCBOR() {
        return _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeArray([
            this.tokenId.toCBOR(),
            this.tokenType.toCBOR(),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeByteString(this._tokenData),
            this.coinData?.toCBOR() ?? _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeNull(),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeTextString(this.recipient),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeByteString(this._salt),
            this.dataHash?.toCBOR() ?? _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeNull(),
            this.reason?.toCBOR() ?? _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_1__.CborEncoder.encodeNull(),
        ]);
    }
    /** Convert instance to readable string */
    toString() {
        return (0,_unicitylabs_commons_lib_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_5__.dedent) `
      MintTransactionData:
        Token ID: ${this.tokenId.toString()}
        Token Type: ${this.tokenType.toString()}
        Token Data: ${_unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(this._tokenData)}
        Coins: ${this.coinData?.toString() ?? null}
        Recipient: ${this.recipient}
        Salt: ${_unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.encode(this.salt)}
        Data: ${this.dataHash?.toString() ?? null}
        Reason: ${this.reason?.toString() ?? null}
        Hash: ${this.hash.toString()}`;
    }
}
//# sourceMappingURL=MintTransactionData.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/OfflineCommitment.js":
/*!*********************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/OfflineCommitment.js ***!
  \*********************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   OfflineCommitment: () => (/* binding */ OfflineCommitment)
/* harmony export */ });
/**
 * Result returned when submitting a transaction to the aggregator.
 */
class OfflineCommitment {
    requestId;
    transactionData;
    authenticator;
    _brand = 'OfflineCommitment';
    /**
     * @param requestId       Request identifier used for submission
     * @param transactionData Submitted transaction data
     * @param authenticator   Signature over the payload
     */
    constructor(requestId, transactionData, authenticator) {
        this.requestId = requestId;
        this.transactionData = transactionData;
        this.authenticator = authenticator;
    }
}
//# sourceMappingURL=OfflineCommitment.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/OfflineTransaction.js":
/*!**********************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/OfflineTransaction.js ***!
  \**********************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   OfflineTransaction: () => (/* binding */ OfflineTransaction)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_api_Authenticator_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/Authenticator.js */ "./node_modules/@unicitylabs/commons/lib/api/Authenticator.js");
/* harmony import */ var _unicitylabs_commons_lib_api_RequestId_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/RequestId.js */ "./node_modules/@unicitylabs/commons/lib/api/RequestId.js");
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_DataHash_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/DataHash.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHash.js");
/* harmony import */ var _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");
/* harmony import */ var _OfflineCommitment_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! ./OfflineCommitment.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/OfflineCommitment.js");
/* harmony import */ var _TransactionData_js__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(/*! ./TransactionData.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/TransactionData.js");
/* harmony import */ var _predicate_PredicateJsonFactory_js__WEBPACK_IMPORTED_MODULE_7__ = __webpack_require__(/*! ../predicate/PredicateJsonFactory.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/predicate/PredicateJsonFactory.js");
/* harmony import */ var _serializer_token_TokenJsonDeserializer_js__WEBPACK_IMPORTED_MODULE_8__ = __webpack_require__(/*! ../serializer/token/TokenJsonDeserializer.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/serializer/token/TokenJsonDeserializer.js");
/* harmony import */ var _token_TokenFactory_js__WEBPACK_IMPORTED_MODULE_9__ = __webpack_require__(/*! ../token/TokenFactory.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/TokenFactory.js");
/* harmony import */ var _token_TokenState_js__WEBPACK_IMPORTED_MODULE_10__ = __webpack_require__(/*! ../token/TokenState.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/token/TokenState.js");
/* harmony import */ var _utils_JsonUtils_js__WEBPACK_IMPORTED_MODULE_11__ = __webpack_require__(/*! ../utils/JsonUtils.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/utils/JsonUtils.js");












/**
 * Represents a transaction with its commitment for offline processing.
 */
class OfflineTransaction {
    commitment;
    token;
    /**
     * @param commitment  The commitment for the transaction
     * @param token
     */
    constructor(commitment, token) {
        this.commitment = commitment;
        this.token = token;
    }
    /**
     * Create OfflineTransaction from JSON data.
     * This properly deserializes all components using the necessary factories.
     * @param data JSON data
     */
    static async fromJSON(data) {
        if (!OfflineTransaction.isJSON(data)) {
            throw new Error('Invalid offline transaction JSON format');
        }
        // Initialize the necessary factories
        const predicateFactory = new _predicate_PredicateJsonFactory_js__WEBPACK_IMPORTED_MODULE_7__.PredicateJsonFactory();
        const tokenFactory = new _token_TokenFactory_js__WEBPACK_IMPORTED_MODULE_9__.TokenFactory(new _serializer_token_TokenJsonDeserializer_js__WEBPACK_IMPORTED_MODULE_8__.TokenJsonDeserializer(predicateFactory));
        // Deserialize the token from JSON
        const token = await tokenFactory.create(data.token);
        // Reconstruct the commitment components
        const requestId = _unicitylabs_commons_lib_api_RequestId_js__WEBPACK_IMPORTED_MODULE_1__.RequestId.fromJSON(data.commitment.requestId);
        const authenticator = _unicitylabs_commons_lib_api_Authenticator_js__WEBPACK_IMPORTED_MODULE_0__.Authenticator.fromJSON(data.commitment.authenticator);
        // Reconstruct the transaction data
        const txData = data.commitment.transactionData;
        const transactionData = await _TransactionData_js__WEBPACK_IMPORTED_MODULE_6__.TransactionData.create(await _token_TokenState_js__WEBPACK_IMPORTED_MODULE_10__.TokenState.create(await predicateFactory.create(token.id, token.type, txData.sourceState.unlockPredicate), txData.sourceState.data ? _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.decode(txData.sourceState.data) : null), txData.recipient, _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.decode(txData.salt), txData.dataHash ? _unicitylabs_commons_lib_hash_DataHash_js__WEBPACK_IMPORTED_MODULE_3__.DataHash.fromJSON(txData.dataHash) : null, txData.message ? _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_4__.HexConverter.decode(txData.message) : null, []);
        // Create the OfflineCommitment
        const offlineCommitment = new _OfflineCommitment_js__WEBPACK_IMPORTED_MODULE_5__.OfflineCommitment(requestId, transactionData, authenticator);
        return new OfflineTransaction(offlineCommitment, token);
    }
    /**
     * Create OfflineTransaction from JSON string.
     * This method can handle JSON strings that were created with toJSONString().
     *
     * @param jsonString JSON string representation
     * @returns Promise<OfflineTransaction>
     */
    static fromJSONString(jsonString) {
        const parsed = _utils_JsonUtils_js__WEBPACK_IMPORTED_MODULE_11__.JsonUtils.parse(jsonString);
        return OfflineTransaction.fromJSON(parsed);
    }
    /**
     * Type guard to check if data is valid OfflineTransaction JSON.
     * @param data Data to validate
     */
    static isJSON(data) {
        return (typeof data === 'object' &&
            data !== null &&
            'commitment' in data &&
            'token' in data &&
            typeof data.commitment === 'object' &&
            typeof data.token === 'object');
    }
    /** Serialize to CBOR format */
    toCBOR() {
        return _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeArray([
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_2__.CborEncoder.encodeArray([
                this.commitment.requestId.toCBOR(),
                this.commitment.transactionData.toCBOR(),
                this.commitment.authenticator.toCBOR(),
            ]),
            this.token.toCBOR(),
        ]);
    }
    /** Serialize to JSON format */
    toJSON() {
        return {
            commitment: {
                authenticator: this.commitment.authenticator.toJSON(),
                requestId: this.commitment.requestId.toJSON(),
                transactionData: this.commitment.transactionData.toJSON(),
            },
            token: this.token.toJSON(),
        };
    }
    /**
     * Serialize to JSON string with BigInt support.
     * This method handles potential BigInt values that might exist in the object graph
     * and converts them to strings to prevent JSON serialization errors.
     *
     * Use this method when you need to serialize for actual transfer (e.g., NFC, file, etc.).
     *
     * @param space Optional spacing for formatting
     * @returns JSON string with BigInt values safely converted
     */
    toJSONString(space) {
        return _utils_JsonUtils_js__WEBPACK_IMPORTED_MODULE_11__.JsonUtils.safeStringify(this, space);
    }
}
//# sourceMappingURL=OfflineTransaction.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/Transaction.js":
/*!***************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/Transaction.js ***!
  \***************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   Transaction: () => (/* binding */ Transaction)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/DataHasher.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHasher.js");
/* harmony import */ var _unicitylabs_commons_lib_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/StringUtils.js */ "./node_modules/@unicitylabs/commons/lib/util/StringUtils.js");



/**
 * A transaction along with its verified inclusion proof.
 */
class Transaction {
    data;
    inclusionProof;
    /**
     * @param data           Transaction data payload
     * @param inclusionProof Proof of inclusion in the ledger
     */
    constructor(data, inclusionProof) {
        this.data = data;
        this.inclusionProof = inclusionProof;
    }
    /** Serialize transaction and proof to JSON. */
    toJSON() {
        return {
            data: this.data.toJSON(),
            inclusionProof: this.inclusionProof.toJSON(),
        };
    }
    /** Serialize transaction and proof to CBOR. */
    toCBOR() {
        return _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeArray([this.data.toCBOR(), this.inclusionProof.toCBOR()]);
    }
    /**
     * Verify if the provided data matches the optional data hash.
     * @param data Data to verify against the transaction's data hash
     */
    async containsData(data) {
        if (this.data.dataHash) {
            if (!data) {
                return false;
            }
            const dataHash = await new _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_1__.DataHasher(this.data.dataHash.algorithm).update(data).digest();
            return dataHash.equals(this.data.dataHash);
        }
        return !data;
    }
    /** Convert instance to readable string */
    toString() {
        return (0,_unicitylabs_commons_lib_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_2__.dedent) `
        Transaction:
          ${this.data.toString()}
          ${this.inclusionProof.toString()}`;
    }
}
//# sourceMappingURL=Transaction.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/TransactionData.js":
/*!*******************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/TransactionData.js ***!
  \*******************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   TransactionData: () => (/* binding */ TransactionData)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/cbor/CborEncoder.js */ "./node_modules/@unicitylabs/commons/lib/cbor/CborEncoder.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/DataHasher.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHasher.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/HashAlgorithm.js */ "./node_modules/@unicitylabs/commons/lib/hash/HashAlgorithm.js");
/* harmony import */ var _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");
/* harmony import */ var _unicitylabs_commons_lib_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/StringUtils.js */ "./node_modules/@unicitylabs/commons/lib/util/StringUtils.js");





/**
 * Data describing a standard token transfer.
 */
class TransactionData {
    hash;
    sourceState;
    recipient;
    salt;
    dataHash;
    _message;
    nameTags;
    /**
     * @param hash        Hash of the encoded data
     * @param sourceState Previous token state
     * @param recipient   Address of the new owner
     * @param salt        Salt used in the new predicate
     * @param dataHash    Optional additional data hash
     * @param _message    Optional message bytes
     * @param nameTags    Optional name tag tokens
     */
    constructor(hash, sourceState, recipient, salt, dataHash, _message, nameTags = []) {
        this.hash = hash;
        this.sourceState = sourceState;
        this.recipient = recipient;
        this.salt = salt;
        this.dataHash = dataHash;
        this._message = _message;
        this.nameTags = nameTags;
        this._message = _message ? new Uint8Array(_message) : null;
        this.nameTags = Array.from(nameTags);
    }
    /** Optional message attached to the transfer. */
    get message() {
        return this._message ? new Uint8Array(this._message) : null;
    }
    /** Hash algorithm for the data hash. */
    get hashAlgorithm() {
        return this.hash.algorithm;
    }
    /**
     * Create a new transaction data object.
     * @param state Token state used as source for the transfer
     * @param recipient Address of the new token owner
     * @param salt Random salt
     * @param dataHash Hash of new token owners data
     * @param message Message bytes
     * @param nameTags
     */
    static async create(state, recipient, salt, dataHash, message, nameTags = []) {
        return new TransactionData(await new _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_1__.DataHasher(_unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_2__.HashAlgorithm.SHA256)
            .update(_unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeArray([
            state.hash.toCBOR(),
            dataHash?.toCBOR() ?? _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeNull(),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeTextString(recipient),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeByteString(salt),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeOptional(message, _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeByteString),
        ]))
            .digest(), state, recipient, salt, dataHash, message, nameTags);
    }
    /** Serialize this token to JSON. */
    toJSON() {
        return {
            dataHash: this.dataHash?.toJSON() ?? null,
            message: this._message ? _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_3__.HexConverter.encode(this._message) : null,
            nameTags: this.nameTags.map((token) => token.toJSON()),
            recipient: this.recipient,
            salt: _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_3__.HexConverter.encode(this.salt),
            sourceState: this.sourceState.toJSON(),
        };
    }
    /** Serialize this token to CBOR. */
    toCBOR() {
        return _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeArray([
            this.sourceState.toCBOR(),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeTextString(this.recipient),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeByteString(this.salt),
            this.dataHash?.toCBOR() ?? _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeNull(),
            this._message ? _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeByteString(this._message) : _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeNull(),
            _unicitylabs_commons_lib_cbor_CborEncoder_js__WEBPACK_IMPORTED_MODULE_0__.CborEncoder.encodeArray(this.nameTags.map((token) => token.toCBOR())),
        ]);
    }
    /** Convert instance to readable string */
    toString() {
        return (0,_unicitylabs_commons_lib_util_StringUtils_js__WEBPACK_IMPORTED_MODULE_4__.dedent) `
      TransactionData:
        ${this.sourceState.toString()}
        Recipient: ${this.recipient.toString()}
        Salt: ${_unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_3__.HexConverter.encode(this.salt)}
        Data: ${this.dataHash?.toString() ?? null}
        Message: ${this._message ? _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_3__.HexConverter.encode(this._message) : null}
        NameTags: [
          ${this.nameTags.map((token) => token.toString()).join('\n')}
        ]
        Hash: ${this.hash.toString()}`;
    }
}
//# sourceMappingURL=TransactionData.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/utils/InclusionProofUtils.js":
/*!*****************************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/utils/InclusionProofUtils.js ***!
  \*****************************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   waitInclusionProof: () => (/* binding */ waitInclusionProof)
/* harmony export */ });
/* harmony import */ var _unicitylabs_commons_lib_api_InclusionProof_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/InclusionProof.js */ "./node_modules/@unicitylabs/commons/lib/api/InclusionProof.js");
/* harmony import */ var _unicitylabs_commons_lib_json_rpc_JsonRpcNetworkError_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/commons/lib/json-rpc/JsonRpcNetworkError.js */ "./node_modules/@unicitylabs/commons/lib/json-rpc/JsonRpcNetworkError.js");


class SleepError extends Error {
    constructor(message) {
        super(message);
        this.name = 'SleepError';
    }
}
function sleep(ms, signal) {
    return new Promise((resolve, reject) => {
        const timeout = setTimeout(resolve, ms);
        signal.addEventListener('abort', () => {
            clearTimeout(timeout);
            reject(signal.reason);
        }, { once: true });
    });
}
async function waitInclusionProof(client, commitment, signal = AbortSignal.timeout(10000), interval = 1000) {
    while (true) {
        try {
            const inclusionProof = await client.getInclusionProof(commitment);
            if ((await inclusionProof.verify(commitment.requestId.toBigInt())) === _unicitylabs_commons_lib_api_InclusionProof_js__WEBPACK_IMPORTED_MODULE_0__.InclusionProofVerificationStatus.OK) {
                return inclusionProof;
            }
        }
        catch (err) {
            if (!(err instanceof _unicitylabs_commons_lib_json_rpc_JsonRpcNetworkError_js__WEBPACK_IMPORTED_MODULE_1__.JsonRpcNetworkError && err.status === 404)) {
                throw err;
            }
        }
        try {
            await sleep(interval, signal);
        }
        catch (err) {
            throw new SleepError(String(err || 'Sleep was aborted'));
        }
    }
}
//# sourceMappingURL=InclusionProofUtils.js.map

/***/ }),

/***/ "./node_modules/@unicitylabs/state-transition-sdk/lib/utils/JsonUtils.js":
/*!*******************************************************************************!*\
  !*** ./node_modules/@unicitylabs/state-transition-sdk/lib/utils/JsonUtils.js ***!
  \*******************************************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   JsonUtils: () => (/* binding */ JsonUtils)
/* harmony export */ });
/**
 * Utility functions for JSON serialization with BigInt support.
 */
class JsonUtils {
    /**
     * JSON.stringify with BigInt support.
     * Converts BigInt values to strings automatically.
     *
     * @param value The value to stringify
     * @param space Optional spacing for formatting
     * @returns JSON string with BigInt values converted to strings
     */
    static stringify(value, space) {
        return JSON.stringify(value, (key, val) => {
            if (typeof val === 'bigint') {
                return val.toString();
            }
            return val;
        }, space);
    }
    /**
     * JSON.parse that can handle BigInt values that were stringified.
     * This is a basic parser - for complex BigInt restoration,
     * use the specific fromJSON methods of each class.
     *
     * @param text The JSON string to parse
     * @returns Parsed object
     */
    static parse(text) {
        return JSON.parse(text);
    }
    /**
     * Safe serialization for objects that might contain BigInt values.
     * First calls toJSON() if available, then applies BigInt-safe stringify.
     *
     * @param obj Object to serialize
     * @param space Optional spacing for formatting
     * @returns JSON string
     */
    static safeStringify(obj, space) {
        // If object has toJSON method, use it first
        if (obj && typeof obj === 'object' && 'toJSON' in obj && typeof obj.toJSON === 'function') {
            const jsonObj = obj.toJSON();
            return JsonUtils.stringify(jsonObj, space);
        }
        // Otherwise use BigInt-safe stringify directly
        return JsonUtils.stringify(obj, space);
    }
}
//# sourceMappingURL=JsonUtils.js.map

/***/ }),

/***/ "./node_modules/uuid/dist/esm-browser/native.js":
/*!******************************************************!*\
  !*** ./node_modules/uuid/dist/esm-browser/native.js ***!
  \******************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   "default": () => (__WEBPACK_DEFAULT_EXPORT__)
/* harmony export */ });
const randomUUID = typeof crypto !== 'undefined' && crypto.randomUUID && crypto.randomUUID.bind(crypto);
/* harmony default export */ const __WEBPACK_DEFAULT_EXPORT__ = ({ randomUUID });


/***/ }),

/***/ "./node_modules/uuid/dist/esm-browser/regex.js":
/*!*****************************************************!*\
  !*** ./node_modules/uuid/dist/esm-browser/regex.js ***!
  \*****************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   "default": () => (__WEBPACK_DEFAULT_EXPORT__)
/* harmony export */ });
/* harmony default export */ const __WEBPACK_DEFAULT_EXPORT__ = (/^(?:[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}|00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff)$/i);


/***/ }),

/***/ "./node_modules/uuid/dist/esm-browser/rng.js":
/*!***************************************************!*\
  !*** ./node_modules/uuid/dist/esm-browser/rng.js ***!
  \***************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   "default": () => (/* binding */ rng)
/* harmony export */ });
let getRandomValues;
const rnds8 = new Uint8Array(16);
function rng() {
    if (!getRandomValues) {
        if (typeof crypto === 'undefined' || !crypto.getRandomValues) {
            throw new Error('crypto.getRandomValues() not supported. See https://github.com/uuidjs/uuid#getrandomvalues-not-supported');
        }
        getRandomValues = crypto.getRandomValues.bind(crypto);
    }
    return getRandomValues(rnds8);
}


/***/ }),

/***/ "./node_modules/uuid/dist/esm-browser/stringify.js":
/*!*********************************************************!*\
  !*** ./node_modules/uuid/dist/esm-browser/stringify.js ***!
  \*********************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   "default": () => (__WEBPACK_DEFAULT_EXPORT__),
/* harmony export */   unsafeStringify: () => (/* binding */ unsafeStringify)
/* harmony export */ });
/* harmony import */ var _validate_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./validate.js */ "./node_modules/uuid/dist/esm-browser/validate.js");

const byteToHex = [];
for (let i = 0; i < 256; ++i) {
    byteToHex.push((i + 0x100).toString(16).slice(1));
}
function unsafeStringify(arr, offset = 0) {
    return (byteToHex[arr[offset + 0]] +
        byteToHex[arr[offset + 1]] +
        byteToHex[arr[offset + 2]] +
        byteToHex[arr[offset + 3]] +
        '-' +
        byteToHex[arr[offset + 4]] +
        byteToHex[arr[offset + 5]] +
        '-' +
        byteToHex[arr[offset + 6]] +
        byteToHex[arr[offset + 7]] +
        '-' +
        byteToHex[arr[offset + 8]] +
        byteToHex[arr[offset + 9]] +
        '-' +
        byteToHex[arr[offset + 10]] +
        byteToHex[arr[offset + 11]] +
        byteToHex[arr[offset + 12]] +
        byteToHex[arr[offset + 13]] +
        byteToHex[arr[offset + 14]] +
        byteToHex[arr[offset + 15]]).toLowerCase();
}
function stringify(arr, offset = 0) {
    const uuid = unsafeStringify(arr, offset);
    if (!(0,_validate_js__WEBPACK_IMPORTED_MODULE_0__["default"])(uuid)) {
        throw TypeError('Stringified UUID is invalid');
    }
    return uuid;
}
/* harmony default export */ const __WEBPACK_DEFAULT_EXPORT__ = (stringify);


/***/ }),

/***/ "./node_modules/uuid/dist/esm-browser/v4.js":
/*!**************************************************!*\
  !*** ./node_modules/uuid/dist/esm-browser/v4.js ***!
  \**************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   "default": () => (__WEBPACK_DEFAULT_EXPORT__)
/* harmony export */ });
/* harmony import */ var _native_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./native.js */ "./node_modules/uuid/dist/esm-browser/native.js");
/* harmony import */ var _rng_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! ./rng.js */ "./node_modules/uuid/dist/esm-browser/rng.js");
/* harmony import */ var _stringify_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! ./stringify.js */ "./node_modules/uuid/dist/esm-browser/stringify.js");



function v4(options, buf, offset) {
    if (_native_js__WEBPACK_IMPORTED_MODULE_0__["default"].randomUUID && !buf && !options) {
        return _native_js__WEBPACK_IMPORTED_MODULE_0__["default"].randomUUID();
    }
    options = options || {};
    const rnds = options.random ?? options.rng?.() ?? (0,_rng_js__WEBPACK_IMPORTED_MODULE_1__["default"])();
    if (rnds.length < 16) {
        throw new Error('Random bytes length must be >= 16');
    }
    rnds[6] = (rnds[6] & 0x0f) | 0x40;
    rnds[8] = (rnds[8] & 0x3f) | 0x80;
    if (buf) {
        offset = offset || 0;
        if (offset < 0 || offset + 16 > buf.length) {
            throw new RangeError(`UUID byte range ${offset}:${offset + 15} is out of buffer bounds`);
        }
        for (let i = 0; i < 16; ++i) {
            buf[offset + i] = rnds[i];
        }
        return buf;
    }
    return (0,_stringify_js__WEBPACK_IMPORTED_MODULE_2__.unsafeStringify)(rnds);
}
/* harmony default export */ const __WEBPACK_DEFAULT_EXPORT__ = (v4);


/***/ }),

/***/ "./node_modules/uuid/dist/esm-browser/validate.js":
/*!********************************************************!*\
  !*** ./node_modules/uuid/dist/esm-browser/validate.js ***!
  \********************************************************/
/***/ ((__unused_webpack___webpack_module__, __webpack_exports__, __webpack_require__) => {

__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   "default": () => (__WEBPACK_DEFAULT_EXPORT__)
/* harmony export */ });
/* harmony import */ var _regex_js__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! ./regex.js */ "./node_modules/uuid/dist/esm-browser/regex.js");

function validate(uuid) {
    return typeof uuid === 'string' && _regex_js__WEBPACK_IMPORTED_MODULE_0__["default"].test(uuid);
}
/* harmony default export */ const __WEBPACK_DEFAULT_EXPORT__ = (validate);


/***/ })

/******/ 	});
/************************************************************************/
/******/ 	// The module cache
/******/ 	var __webpack_module_cache__ = {};
/******/ 	
/******/ 	// The require function
/******/ 	function __webpack_require__(moduleId) {
/******/ 		// Check if module is in cache
/******/ 		var cachedModule = __webpack_module_cache__[moduleId];
/******/ 		if (cachedModule !== undefined) {
/******/ 			return cachedModule.exports;
/******/ 		}
/******/ 		// Create a new module (and put it into the cache)
/******/ 		var module = __webpack_module_cache__[moduleId] = {
/******/ 			// no module.id needed
/******/ 			// no module.loaded needed
/******/ 			exports: {}
/******/ 		};
/******/ 	
/******/ 		// Execute the module function
/******/ 		__webpack_modules__[moduleId](module, module.exports, __webpack_require__);
/******/ 	
/******/ 		// Return the exports of the module
/******/ 		return module.exports;
/******/ 	}
/******/ 	
/************************************************************************/
/******/ 	/* webpack/runtime/define property getters */
/******/ 	(() => {
/******/ 		// define getter functions for harmony exports
/******/ 		__webpack_require__.d = (exports, definition) => {
/******/ 			for(var key in definition) {
/******/ 				if(__webpack_require__.o(definition, key) && !__webpack_require__.o(exports, key)) {
/******/ 					Object.defineProperty(exports, key, { enumerable: true, get: definition[key] });
/******/ 				}
/******/ 			}
/******/ 		};
/******/ 	})();
/******/ 	
/******/ 	/* webpack/runtime/hasOwnProperty shorthand */
/******/ 	(() => {
/******/ 		__webpack_require__.o = (obj, prop) => (Object.prototype.hasOwnProperty.call(obj, prop))
/******/ 	})();
/******/ 	
/******/ 	/* webpack/runtime/make namespace object */
/******/ 	(() => {
/******/ 		// define __esModule on exports
/******/ 		__webpack_require__.r = (exports) => {
/******/ 			if(typeof Symbol !== 'undefined' && Symbol.toStringTag) {
/******/ 				Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' });
/******/ 			}
/******/ 			Object.defineProperty(exports, '__esModule', { value: true });
/******/ 		};
/******/ 	})();
/******/ 	
/************************************************************************/
var __webpack_exports__ = {};
// This entry needs to be wrapped in an IIFE because it needs to be isolated against other modules in the chunk.
(() => {
/*!**********************!*\
  !*** ./src/index.ts ***!
  \**********************/
__webpack_require__.r(__webpack_exports__);
/* harmony export */ __webpack_require__.d(__webpack_exports__, {
/* harmony export */   AddressScheme: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.AddressScheme),
/* harmony export */   AggregatorClient: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.AggregatorClient),
/* harmony export */   Authenticator: () => (/* reexport safe */ _unicitylabs_commons_lib_api_Authenticator_js__WEBPACK_IMPORTED_MODULE_14__.Authenticator),
/* harmony export */   BurnPredicate: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.BurnPredicate),
/* harmony export */   CoinId: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.CoinId),
/* harmony export */   Commitment: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.Commitment),
/* harmony export */   DataHash: () => (/* reexport safe */ _unicitylabs_commons_lib_hash_DataHash_js__WEBPACK_IMPORTED_MODULE_10__.DataHash),
/* harmony export */   DataHasher: () => (/* reexport safe */ _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_9__.DataHasher),
/* harmony export */   DefaultPredicate: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.DefaultPredicate),
/* harmony export */   DirectAddress: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.DirectAddress),
/* harmony export */   HashAlgorithm: () => (/* reexport safe */ _unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_8__.HashAlgorithm),
/* harmony export */   HexConverter: () => (/* reexport safe */ _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_11__.HexConverter),
/* harmony export */   InclusionProof: () => (/* reexport safe */ _unicitylabs_commons_lib_api_InclusionProof_js__WEBPACK_IMPORTED_MODULE_12__.InclusionProof),
/* harmony export */   InclusionProofVerificationStatus: () => (/* reexport safe */ _unicitylabs_commons_lib_api_InclusionProof_js__WEBPACK_IMPORTED_MODULE_12__.InclusionProofVerificationStatus),
/* harmony export */   MINTER_SECRET: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.MINTER_SECRET),
/* harmony export */   MaskedPredicate: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.MaskedPredicate),
/* harmony export */   MintTransactionData: () => (/* reexport safe */ _unicitylabs_state_transition_sdk_lib_transaction_MintTransactionData_js__WEBPACK_IMPORTED_MODULE_4__.MintTransactionData),
/* harmony export */   NameTagTokenData: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.NameTagTokenData),
/* harmony export */   OfflineCommitment: () => (/* reexport safe */ _unicitylabs_state_transition_sdk_lib_transaction_OfflineCommitment_js__WEBPACK_IMPORTED_MODULE_2__.OfflineCommitment),
/* harmony export */   OfflineStateTransitionClient: () => (/* reexport safe */ _unicitylabs_state_transition_sdk_lib_OfflineStateTransitionClient_js__WEBPACK_IMPORTED_MODULE_1__.OfflineStateTransitionClient),
/* harmony export */   OfflineTransaction: () => (/* reexport safe */ _unicitylabs_state_transition_sdk_lib_transaction_OfflineTransaction_js__WEBPACK_IMPORTED_MODULE_3__.OfflineTransaction),
/* harmony export */   PredicateJsonFactory: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.PredicateJsonFactory),
/* harmony export */   PredicateType: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.PredicateType),
/* harmony export */   RequestId: () => (/* reexport safe */ _unicitylabs_commons_lib_api_RequestId_js__WEBPACK_IMPORTED_MODULE_13__.RequestId),
/* harmony export */   Signature: () => (/* reexport safe */ _unicitylabs_commons_lib_signing_Signature_js__WEBPACK_IMPORTED_MODULE_7__.Signature),
/* harmony export */   SigningService: () => (/* reexport safe */ _unicitylabs_commons_lib_signing_SigningService_js__WEBPACK_IMPORTED_MODULE_6__.SigningService),
/* harmony export */   StateTransitionClient: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.StateTransitionClient),
/* harmony export */   SubmitCommitmentRequest: () => (/* reexport safe */ _unicitylabs_commons_lib_api_SubmitCommitmentRequest_js__WEBPACK_IMPORTED_MODULE_15__.SubmitCommitmentRequest),
/* harmony export */   SubmitCommitmentResponse: () => (/* reexport safe */ _unicitylabs_commons_lib_api_SubmitCommitmentResponse_js__WEBPACK_IMPORTED_MODULE_16__.SubmitCommitmentResponse),
/* harmony export */   SubmitCommitmentStatus: () => (/* reexport safe */ _unicitylabs_commons_lib_api_SubmitCommitmentResponse_js__WEBPACK_IMPORTED_MODULE_16__.SubmitCommitmentStatus),
/* harmony export */   TOKEN_VERSION: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.TOKEN_VERSION),
/* harmony export */   Token: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.Token),
/* harmony export */   TokenCoinData: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.TokenCoinData),
/* harmony export */   TokenFactory: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.TokenFactory),
/* harmony export */   TokenId: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.TokenId),
/* harmony export */   TokenJsonDeserializer: () => (/* reexport safe */ _unicitylabs_state_transition_sdk_lib_serializer_token_TokenJsonDeserializer_js__WEBPACK_IMPORTED_MODULE_5__.TokenJsonDeserializer),
/* harmony export */   TokenState: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.TokenState),
/* harmony export */   TokenType: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.TokenType),
/* harmony export */   Transaction: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.Transaction),
/* harmony export */   TransactionData: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.TransactionData),
/* harmony export */   UnmaskedPredicate: () => (/* reexport safe */ _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__.UnmaskedPredicate)
/* harmony export */ });
/* harmony import */ var _unicitylabs_state_transition_sdk__WEBPACK_IMPORTED_MODULE_0__ = __webpack_require__(/*! @unicitylabs/state-transition-sdk */ "./node_modules/@unicitylabs/state-transition-sdk/lib/index.js");
/* harmony import */ var _unicitylabs_state_transition_sdk_lib_OfflineStateTransitionClient_js__WEBPACK_IMPORTED_MODULE_1__ = __webpack_require__(/*! @unicitylabs/state-transition-sdk/lib/OfflineStateTransitionClient.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/OfflineStateTransitionClient.js");
/* harmony import */ var _unicitylabs_state_transition_sdk_lib_transaction_OfflineCommitment_js__WEBPACK_IMPORTED_MODULE_2__ = __webpack_require__(/*! @unicitylabs/state-transition-sdk/lib/transaction/OfflineCommitment.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/OfflineCommitment.js");
/* harmony import */ var _unicitylabs_state_transition_sdk_lib_transaction_OfflineTransaction_js__WEBPACK_IMPORTED_MODULE_3__ = __webpack_require__(/*! @unicitylabs/state-transition-sdk/lib/transaction/OfflineTransaction.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/OfflineTransaction.js");
/* harmony import */ var _unicitylabs_state_transition_sdk_lib_transaction_MintTransactionData_js__WEBPACK_IMPORTED_MODULE_4__ = __webpack_require__(/*! @unicitylabs/state-transition-sdk/lib/transaction/MintTransactionData.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/transaction/MintTransactionData.js");
/* harmony import */ var _unicitylabs_state_transition_sdk_lib_serializer_token_TokenJsonDeserializer_js__WEBPACK_IMPORTED_MODULE_5__ = __webpack_require__(/*! @unicitylabs/state-transition-sdk/lib/serializer/token/TokenJsonDeserializer.js */ "./node_modules/@unicitylabs/state-transition-sdk/lib/serializer/token/TokenJsonDeserializer.js");
/* harmony import */ var _unicitylabs_commons_lib_signing_SigningService_js__WEBPACK_IMPORTED_MODULE_6__ = __webpack_require__(/*! @unicitylabs/commons/lib/signing/SigningService.js */ "./node_modules/@unicitylabs/commons/lib/signing/SigningService.js");
/* harmony import */ var _unicitylabs_commons_lib_signing_Signature_js__WEBPACK_IMPORTED_MODULE_7__ = __webpack_require__(/*! @unicitylabs/commons/lib/signing/Signature.js */ "./node_modules/@unicitylabs/commons/lib/signing/Signature.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_HashAlgorithm_js__WEBPACK_IMPORTED_MODULE_8__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/HashAlgorithm.js */ "./node_modules/@unicitylabs/commons/lib/hash/HashAlgorithm.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_DataHasher_js__WEBPACK_IMPORTED_MODULE_9__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/DataHasher.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHasher.js");
/* harmony import */ var _unicitylabs_commons_lib_hash_DataHash_js__WEBPACK_IMPORTED_MODULE_10__ = __webpack_require__(/*! @unicitylabs/commons/lib/hash/DataHash.js */ "./node_modules/@unicitylabs/commons/lib/hash/DataHash.js");
/* harmony import */ var _unicitylabs_commons_lib_util_HexConverter_js__WEBPACK_IMPORTED_MODULE_11__ = __webpack_require__(/*! @unicitylabs/commons/lib/util/HexConverter.js */ "./node_modules/@unicitylabs/commons/lib/util/HexConverter.js");
/* harmony import */ var _unicitylabs_commons_lib_api_InclusionProof_js__WEBPACK_IMPORTED_MODULE_12__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/InclusionProof.js */ "./node_modules/@unicitylabs/commons/lib/api/InclusionProof.js");
/* harmony import */ var _unicitylabs_commons_lib_api_RequestId_js__WEBPACK_IMPORTED_MODULE_13__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/RequestId.js */ "./node_modules/@unicitylabs/commons/lib/api/RequestId.js");
/* harmony import */ var _unicitylabs_commons_lib_api_Authenticator_js__WEBPACK_IMPORTED_MODULE_14__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/Authenticator.js */ "./node_modules/@unicitylabs/commons/lib/api/Authenticator.js");
/* harmony import */ var _unicitylabs_commons_lib_api_SubmitCommitmentRequest_js__WEBPACK_IMPORTED_MODULE_15__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/SubmitCommitmentRequest.js */ "./node_modules/@unicitylabs/commons/lib/api/SubmitCommitmentRequest.js");
/* harmony import */ var _unicitylabs_commons_lib_api_SubmitCommitmentResponse_js__WEBPACK_IMPORTED_MODULE_16__ = __webpack_require__(/*! @unicitylabs/commons/lib/api/SubmitCommitmentResponse.js */ "./node_modules/@unicitylabs/commons/lib/api/SubmitCommitmentResponse.js");

// Explicitly export offline classes that might not be in the main export



// Explicitly export transaction classes

// Explicitly export deserializer classes

// export * from './ISerializable.js';
// export * from './StateTransitionClient.js';
// export * from './hash/createDefaultDataHasherFactory.js';
// Commons exports - Signing


// Commons exports - Hashing



// Commons exports - Utilities

// Commons exports - API/Inclusion Proof






})();

/******/ 	return __webpack_exports__;
/******/ })()
;
});
//# sourceMappingURL=unicity-sdk.js.map