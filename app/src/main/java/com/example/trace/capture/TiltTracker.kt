package com.example.trace.capture

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Tracks device tilt from gravity / rotation-vector readings and reports
 * angular deviation from a learned reference orientation.
 *
 * Ported from ECHO's TiltTracker. Uses a gravity vector reference (or
 * rotation-vector quaternion fallback) with a deadband so tiny orientation
 * jitter doesn't produce false TILT_CHANGE events.
 *
 * Thread-safe: all public methods are synchronized.
 */
class TiltTracker(
    /** Degrees of change before a non-zero angle is reported. */
    private val deadbandDeg: Double = 3.0,
) {
    private var refGravityX = 0.0
    private var refGravityY = 0.0
    private var refGravityZ = 0.0
    private var hasGravityRef = false

    private var refQw = 1.0
    private var refQx = 0.0
    private var refQy = 0.0
    private var refQz = 0.0
    private var hasQuatRef = false

    /** Angle (degrees) between the current gravity vector and the stored reference. */
    @Synchronized
    fun angleFromGravityReference(gx: Double, gy: Double, gz: Double): Double {
        if (!hasGravityRef) {
            refGravityX = gx; refGravityY = gy; refGravityZ = gz
            hasGravityRef = true
            return 0.0
        }
        val dot = gx * refGravityX + gy * refGravityY + gz * refGravityZ
        val mag = sqrt(gx * gx + gy * gy + gz * gz) *
            sqrt(refGravityX * refGravityX + refGravityY * refGravityY + refGravityZ * refGravityZ)
        val angle = if (mag > 0.0) {
            Math.toDegrees(kotlin.math.acos((dot / mag).coerceIn(-1.0, 1.0)))
        } else 0.0
        return if (angle < deadbandDeg) 0.0 else angle
    }

    /**
     * Stores the current gravity vector as the reference orientation.
     * Call this when the phone is known to be in its resting position.
     */
    @Synchronized
    fun updateGravity(gx: Double, gy: Double, gz: Double) {
        refGravityX = gx; refGravityY = gy; refGravityZ = gz
        hasGravityRef = true
    }

    /**
     * Angle (degrees) between the current rotation-quaternion and the
     * stored reference.
     */
    @Synchronized
    fun angleFromQuaternionReference(qx: Double, qy: Double, qz: Double, qw: Double): Double {
        if (!hasQuatRef) {
            refQx = qx; refQy = qy; refQz = qz; refQw = qw
            hasQuatRef = true
            return 0.0
        }
        // Relative rotation q_current * q_reference_conj
        val rw = refQw; val rx = -refQx; val ry = -refQy; val rz = -refQz
        val tqw = qw * rw - qx * rx - qy * ry - qz * rz
        val tqx = qw * rx + qx * rw + qy * rz - qz * ry
        val tqy = qw * ry - qx * rz + qy * rw + qz * rx
        val tqz = qw * rz + qx * ry - qy * rx + qz * rw

        val angleDeg = Math.toDegrees(2.0 * atan2(
            sqrt(maxOf(0.0, tqx * tqx + tqy * tqy + tqz * tqz)),
            tqw.coerceIn(-1.0, 1.0),
        ))
        return if (angleDeg < deadbandDeg) 0.0 else angleDeg
    }

    /**
     * Stores the current quaternion as the reference orientation.
     */
    @Synchronized
    fun updateQuaternion(qx: Double, qy: Double, qz: Double, qw: Double) {
        refQx = qx; refQy = qy; refQz = qz; refQw = qw
        hasQuatRef = true
    }

    /** Reset the reference to the next reading. */
    @Synchronized
    fun reset() {
        hasGravityRef = false
        hasQuatRef = false
    }
}