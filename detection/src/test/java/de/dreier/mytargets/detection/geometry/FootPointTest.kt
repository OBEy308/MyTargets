/*
 * Copyright (C) 2026 MyTargets contributors
 *
 * This file is part of MyTargets.
 *
 * MyTargets is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2
 * as published by the Free Software Foundation.
 *
 * MyTargets is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package de.dreier.mytargets.detection.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FootPointTest {

    /**
     * A pinhole camera with R = Rx(pitch) Ry(yaw): a face point X, z = 0,
     * sits at R X + t in camera coordinates. The camera centre is -R^T t in
     * target coordinates (z into the face); its x and y are the foot point.
     */
    private class Pose(yawDegrees: Double, pitchDegrees: Double, private val t: Vec3) {
        private val r: Mat3

        init {
            val a = Math.toRadians(yawDegrees)
            val b = Math.toRadians(pitchDegrees)
            val ry = Mat3.of(cos(a), 0.0, sin(a), 0.0, 1.0, 0.0, -sin(a), 0.0, cos(a))
            val rx = Mat3.of(1.0, 0.0, 0.0, 0.0, cos(b), -sin(b), 0.0, sin(b), cos(b))
            r = rx * ry
        }

        val intrinsics = CameraIntrinsics(1500.0, Vec2(2000.0, 1500.0))

        val imageToTarget: Mat3
            get() {
                val k = Mat3.of(1500.0, 0.0, 2000.0, 0.0, 1500.0, 1500.0, 0.0, 0.0, 1.0)
                val columns = Mat3.of(
                    r[0, 0], r[0, 1], t.x,
                    r[1, 0], r[1, 1], t.y,
                    r[2, 0], r[2, 1], t.z
                )
                return requireNotNull((k * columns).inverse())
            }

        val footPoint: Vec2
            get() {
                val back = r.transpose() * t
                return Vec2(-back.x, -back.y)
            }
    }

    @Test
    fun aFrontalCameraStandsOverItsFootPoint() {
        val pose = Pose(0.0, 0.0, Vec3(0.3, -0.2, 3.0))

        val q = FootPoint.of(pose.imageToTarget, pose.intrinsics)!!

        assertThat(q.x).isWithin(1e-7).of(-0.3)
        assertThat(q.y).isWithin(1e-7).of(0.2)
    }

    @Test
    fun at30DegreesTheFootPointLiesOutsideTheFaceOnTheCameraSide() {
        // SyntheticFace.view's camera: turned about the vertical axis, 3.75 radii from the centre.
        val pose = Pose(30.0, 0.0, Vec3(0.0, 0.0, 3.75))

        val q = FootPoint.of(pose.imageToTarget, pose.intrinsics)!!

        assertThat(q.x).isWithin(1e-7).of(3.75 * sin(Math.toRadians(30.0)))
        assertThat(q.y).isWithin(1e-7).of(0.0)
    }

    @Test
    fun aGeneralPoseGivesTheFootOfItsPerpendicular() {
        val pose = Pose(20.0, -15.0, Vec3(0.1, 0.05, 2.5))

        val q = FootPoint.of(pose.imageToTarget, pose.intrinsics)!!

        assertThat(q.distanceTo(pose.footPoint)).isLessThan(1e-7)
    }

    @Test
    fun theScaleOfTheHomographyDoesNotMatter() {
        val pose = Pose(20.0, -15.0, Vec3(0.1, 0.05, 2.5))

        val q = FootPoint.of(pose.imageToTarget.scaled(-2.5), pose.intrinsics)!!

        assertThat(q.distanceTo(pose.footPoint)).isLessThan(1e-7)
    }
}
