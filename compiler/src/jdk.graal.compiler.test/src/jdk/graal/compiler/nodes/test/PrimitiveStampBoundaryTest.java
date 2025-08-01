/*
 * Copyright (c) 2012, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.graal.compiler.nodes.test;

import java.util.EnumSet;
import java.util.Set;

import org.junit.Test;

import jdk.graal.compiler.core.common.calc.FloatConvert;
import jdk.graal.compiler.core.common.calc.FloatConvertCategory;
import jdk.graal.compiler.core.common.type.ArithmeticOpTable;
import jdk.graal.compiler.core.common.type.ArithmeticOpTable.BinaryOp;
import jdk.graal.compiler.core.common.type.ArithmeticOpTable.IntegerConvertOp;
import jdk.graal.compiler.core.common.type.ArithmeticOpTable.ShiftOp;
import jdk.graal.compiler.core.common.type.FloatStamp;
import jdk.graal.compiler.core.common.type.IntegerStamp;
import jdk.graal.compiler.core.common.type.PrimitiveStamp;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.test.GraalTest;
import jdk.graal.compiler.util.EconomicHashSet;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;

/**
 * Exercise the various stamp folding operations by generating ranges from a set of boundary values
 * and then ensuring that the values that produced those ranges are in the resulting stamp.
 */
public class PrimitiveStampBoundaryTest extends GraalTest {

    static long[] longBoundaryValues = {Long.MIN_VALUE, Long.MIN_VALUE + 1, Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -1, 0, 1, Integer.MAX_VALUE - 1, Integer.MAX_VALUE, Long.MAX_VALUE - 1,
                    Long.MAX_VALUE};

    static int[] shiftBoundaryValues = {-128, -1, 0, 1, 4, 8, 16, 31, 63, 128};

    static Set<IntegerStamp> shiftStamps;
    static Set<PrimitiveStamp> integerTestStamps;
    static Set<PrimitiveStamp> floatTestStamps;

    static {
        shiftStamps = new EconomicHashSet<>();
        for (long v1 : shiftBoundaryValues) {
            for (long v2 : shiftBoundaryValues) {
                shiftStamps.add(IntegerStamp.create(32, Math.min(v1, v2), Math.max(v1, v2)));
            }
        }
        shiftStamps.add((IntegerStamp) StampFactory.empty(JavaKind.Int));

        integerTestStamps = new EconomicHashSet<>();
        for (long v1 : longBoundaryValues) {
            for (long v2 : longBoundaryValues) {
                if (v2 == (int) v2 && v1 == (int) v1) {
                    integerTestStamps.add(IntegerStamp.create(32, Math.min(v1, v2), Math.max(v1, v2)));
                }
                integerTestStamps.add(IntegerStamp.create(64, Math.min(v1, v2), Math.max(v1, v2)));
            }
        }
        integerTestStamps.add((PrimitiveStamp) StampFactory.empty(JavaKind.Int));
        integerTestStamps.add((PrimitiveStamp) StampFactory.empty(JavaKind.Long));
    }

    static double[] doubleBoundaryValues = {Double.NEGATIVE_INFINITY, Double.MIN_VALUE, Float.NEGATIVE_INFINITY, Float.MIN_VALUE,
                    Long.MIN_VALUE, Long.MIN_VALUE + 1, Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -1, -0.0, +0.0, 1,
                    Integer.MAX_VALUE - 1, Integer.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE,
                    Float.MAX_VALUE, Float.POSITIVE_INFINITY, Double.MAX_VALUE, Double.POSITIVE_INFINITY};

    static double[] doubleSpecialValues = {Double.NaN, -0.0, -0.0F, Float.NaN};

    static {
        floatTestStamps = new EconomicHashSet<>();

        for (double d1 : doubleBoundaryValues) {
            for (double d2 : doubleBoundaryValues) {
                float f1 = (float) d2;
                float f2 = (float) d1;
                if (d2 == f1 && d1 == f2) {
                    generateFloatingStamps(FloatStamp.create(32, Math.min(f2, f1), Math.max(f2, f1), true));
                    generateFloatingStamps(FloatStamp.create(32, Math.min(f2, f1), Math.max(f2, f1), false));
                }
                generateFloatingStamps(FloatStamp.create(64, Math.min(d1, d2), Math.max(d1, d2), true));
                generateFloatingStamps(FloatStamp.create(64, Math.min(d1, d2), Math.max(d1, d2), false));
            }
        }
        floatTestStamps.add((PrimitiveStamp) StampFactory.empty(JavaKind.Float));
        floatTestStamps.add((PrimitiveStamp) StampFactory.empty(JavaKind.Double));
        floatTestStamps.add(FloatStamp.createNaN(Float.SIZE));
        floatTestStamps.add(FloatStamp.createNaN(Double.SIZE));
    }

    private static void generateFloatingStamps(FloatStamp floatStamp) {
        floatTestStamps.add(floatStamp);
        for (double d : doubleSpecialValues) {
            FloatStamp newStamp = (FloatStamp) floatStamp.meet(floatStampForConstant(d, floatStamp.getBits()));
            if (!newStamp.isUnrestricted()) {
                floatTestStamps.add(newStamp);
            }
        }
    }

    @Test
    public void testConvertBoundaryValues() {
        testConvertBoundaryValues(IntegerStamp.OPS.getSignExtend(), 32, 64, integerTestStamps);
        testConvertBoundaryValues(IntegerStamp.OPS.getZeroExtend(), 32, 64, integerTestStamps);
        testConvertBoundaryValues(IntegerStamp.OPS.getNarrow(), 64, 32, integerTestStamps);
    }

    private static void testConvertBoundaryValues(IntegerConvertOp<?> op, int inputBits, int resultBits, Set<PrimitiveStamp> stamps) {
        for (PrimitiveStamp stamp : stamps) {
            if (inputBits == stamp.getBits()) {
                Stamp lower = boundaryStamp(stamp, false);
                Stamp upper = boundaryStamp(stamp, true);
                checkConvertOperation(op, inputBits, resultBits, op.foldStamp(inputBits, resultBits, stamp), lower);
                checkConvertOperation(op, inputBits, resultBits, op.foldStamp(inputBits, resultBits, stamp), upper);
            }
        }
    }

    private static void checkConvertOperation(IntegerConvertOp<?> op, int inputBits, int resultBits, Stamp result, Stamp v1stamp) {
        Stamp folded = op.foldStamp(inputBits, resultBits, v1stamp);
        assertTrue(folded.isEmpty() || folded.asConstant() != null, "should constant fold %s %s %s", op, v1stamp, folded);
        assertTrue(result.meet(folded).equals(result), "result out of range %s %s %s %s %s", op, v1stamp, folded, result, result.meet(folded));
    }

    @Test
    public void testFloatConvertBoundaryValues() {
        for (FloatConvert op : EnumSet.allOf(FloatConvert.class)) {
            ArithmeticOpTable.FloatConvertOp floatConvert = IntegerStamp.OPS.getFloatConvert(op);
            if (floatConvert == null) {
                continue;
            }
            assert op.getCategory() == FloatConvertCategory.IntegerToFloatingPoint : op;
            testConvertBoundaryValues(floatConvert, op.getInputBits(), integerTestStamps);
        }
        for (FloatConvert op : EnumSet.allOf(FloatConvert.class)) {
            ArithmeticOpTable.FloatConvertOp floatConvert = FloatStamp.OPS.getFloatConvert(op);
            if (floatConvert == null) {
                continue;
            }
            assert op.getCategory() == FloatConvertCategory.FloatingPointToInteger || op.getCategory() == FloatConvertCategory.FloatingPointToFloatingPoint : op;
            testConvertBoundaryValues(floatConvert, op.getInputBits(), floatTestStamps);
        }
    }

    private static void testConvertBoundaryValues(ArithmeticOpTable.FloatConvertOp op, int bits, Set<PrimitiveStamp> stamps) {
        for (PrimitiveStamp stamp : stamps) {
            if (bits == stamp.getBits()) {
                Stamp lower = boundaryStamp(stamp, false);
                Stamp upper = boundaryStamp(stamp, true);
                checkConvertOperation(op, op.foldStamp(stamp), lower);
                checkConvertOperation(op, op.foldStamp(stamp), upper);
            }
        }

    }

    static void shouldConstantFold(boolean b, Stamp folded, Object o, Stamp s1) {
        assertTrue(b || constantFloatStampMayNotFold(folded), "should constant fold %s %s %s", o, s1, folded);
    }

    private static boolean constantFloatStampMayNotFold(Stamp s) {
        if (s instanceof FloatStamp f) {
            return f.lowerBound() == 0 || f.isNaN();
        }
        return false;
    }

    private static void checkConvertOperation(ArithmeticOpTable.FloatConvertOp op, Stamp result, Stamp v1stamp) {
        Stamp folded = op.foldStamp(v1stamp);
        shouldConstantFold(folded.isEmpty() || folded.asConstant() != null, folded, op, v1stamp);
        assertTrue(result.meet(folded).equals(result), "result out of range %s %s %s %s %s", op, v1stamp, folded, result, result.meet(folded));
    }

    @Test
    public void testShiftBoundaryValues() {
        for (ShiftOp<?> op : IntegerStamp.OPS.getShiftOps()) {
            testShiftBoundaryValues(op, integerTestStamps, shiftStamps);
        }
    }

    private static void testShiftBoundaryValues(ShiftOp<?> shiftOp, Set<PrimitiveStamp> stamps, Set<IntegerStamp> shifts) {
        for (PrimitiveStamp testStamp : stamps) {
            if (testStamp instanceof IntegerStamp) {
                IntegerStamp stamp = (IntegerStamp) testStamp;
                for (IntegerStamp shiftStamp : shifts) {
                    IntegerStamp foldedStamp = (IntegerStamp) shiftOp.foldStamp(stamp, shiftStamp);
                    if (foldedStamp.isEmpty()) {
                        assertTrue(stamp.isEmpty() || shiftStamp.isEmpty());
                        continue;
                    }
                    checkShiftOperation(stamp.getBits(), shiftOp, foldedStamp, stamp.lowerBound(), shiftStamp.lowerBound());
                    checkShiftOperation(stamp.getBits(), shiftOp, foldedStamp, stamp.lowerBound(), shiftStamp.upperBound());
                    checkShiftOperation(stamp.getBits(), shiftOp, foldedStamp, stamp.upperBound(), shiftStamp.lowerBound());
                    checkShiftOperation(stamp.getBits(), shiftOp, foldedStamp, stamp.upperBound(), shiftStamp.upperBound());
                }
            }
        }
    }

    private static void checkShiftOperation(int bits, ShiftOp<?> op, IntegerStamp result, long v1, long v2) {
        IntegerStamp v1stamp = IntegerStamp.create(bits, v1, v1);
        IntegerStamp v2stamp = IntegerStamp.create(32, v2, v2);
        IntegerStamp folded = (IntegerStamp) op.foldStamp(v1stamp, v2stamp);
        Constant constant = op.foldConstant(JavaConstant.forPrimitiveInt(bits, v1), JavaConstant.forInt((int) v2));
        assertTrue(constant != null);
        assertTrue(folded.asConstant() != null, "should constant fold %s %s %s %s", op, v1stamp, v2stamp, folded);
        assertTrue(result.meet(folded).equals(result), "result out of range %s %s %s %s %s %s", op, v1stamp, v2stamp, folded, result, result.meet(folded));
    }

    private static void checkBinaryOperation(ArithmeticOpTable.BinaryOp<?> op, Stamp result, Stamp v1stamp, Stamp v2stamp) {
        Stamp folded = op.foldStamp(v1stamp, v2stamp);
        if (v1stamp.isEmpty() || v2stamp.isEmpty()) {
            assertTrue(folded.isEmpty());
            return;
        }
        if (constantFloatStampMayNotFold(v1stamp) || constantFloatStampMayNotFold(v2stamp)) {
            return;
        }
        Constant constant = op.foldConstant(v1stamp.asConstant(), v2stamp.asConstant());
        if (constant != null) {
            assertFalse(folded.isEmpty());
            Constant constant2 = folded.asConstant();
            if (constant2 == null && v1stamp instanceof FloatStamp) {
                JavaConstant c = (JavaConstant) constant;
                assertTrue((c.getJavaKind() == JavaKind.Double && (Double.isNaN(c.asDouble()) || c.asDouble() == 0) ||
                                (c.getJavaKind() == JavaKind.Float && (Float.isNaN(c.asFloat()) || c.asFloat() == 0))));
                return;
            }

            assertTrue(constant2 != null, "should constant fold %s %s %s %s", op, v1stamp, v2stamp, folded);
            assertTrue(constant.equals(constant2), "should produce same constant %s %s %s %s %s", op, v1stamp, v2stamp, constant, constant2);
            assertTrue(result.meet(folded).equals(result), "result out of range %s %s %s %s %s %s", op, v1stamp, v2stamp, folded, result, result.meet(folded));
        }
    }

    @Test
    public void testBinaryBoundaryValues() {
        for (BinaryOp<?> op : IntegerStamp.OPS.getBinaryOps()) {
            if (op != null) {
                testBinaryBoundaryValues(op, integerTestStamps);
            }
        }
        for (BinaryOp<?> op : FloatStamp.OPS.getBinaryOps()) {
            if (op != null) {
                testBinaryBoundaryValues(op, floatTestStamps);
            }
        }
    }

    private static Stamp boundaryStamp(Stamp v1, boolean upper) {
        if (v1.isEmpty()) {
            return v1;
        }
        if (v1 instanceof IntegerStamp) {
            IntegerStamp istamp = (IntegerStamp) v1;
            long bound = upper ? istamp.upperBound() : istamp.lowerBound();
            return IntegerStamp.create(istamp.getBits(), bound, bound);
        } else if (v1 instanceof FloatStamp) {
            FloatStamp floatStamp = (FloatStamp) v1;
            double bound = upper ? floatStamp.upperBound() : floatStamp.lowerBound();
            int bits = floatStamp.getBits();
            return floatStampForConstant(bound, bits);
        } else {
            throw new InternalError("unexpected stamp type " + v1);
        }
    }

    private static FloatStamp floatStampForConstant(double bound, int bits) {
        if (bits == 32) {
            float fbound = (float) bound;
            return FloatStamp.create(bits, fbound, fbound, !Float.isNaN(fbound));
        } else {
            return FloatStamp.create(bits, bound, bound, !Double.isNaN(bound));
        }
    }

    private static void testBinaryBoundaryValues(ArithmeticOpTable.BinaryOp<?> op, Set<PrimitiveStamp> stamps) {
        for (PrimitiveStamp v1 : stamps) {
            for (PrimitiveStamp v2 : stamps) {
                if (v1.getBits() == v2.getBits() && v1.getClass() == v2.getClass()) {
                    Stamp result = op.foldStamp(v1, v2);
                    Stamp v1lower = boundaryStamp(v1, false);
                    Stamp v1upper = boundaryStamp(v1, true);
                    Stamp v2lower = boundaryStamp(v2, false);
                    Stamp v2upper = boundaryStamp(v2, true);
                    checkBinaryOperation(op, result, v1lower, v2lower);
                    checkBinaryOperation(op, result, v1lower, v2upper);
                    checkBinaryOperation(op, result, v1upper, v2lower);
                    checkBinaryOperation(op, result, v1upper, v2upper);
                }
            }
        }
    }

    @Test
    public void testUnaryBoundaryValues() {
        for (ArithmeticOpTable.UnaryOp<?> op : IntegerStamp.OPS.getUnaryOps()) {
            if (op != null) {
                testUnaryBoundaryValues(op, integerTestStamps);
            }
        }
        for (ArithmeticOpTable.UnaryOp<?> op : FloatStamp.OPS.getUnaryOps()) {
            if (op != null) {
                testUnaryBoundaryValues(op, floatTestStamps);
            }
        }
    }

    private static void testUnaryBoundaryValues(ArithmeticOpTable.UnaryOp<?> op, Set<PrimitiveStamp> stamps) {
        for (PrimitiveStamp v1 : stamps) {
            Stamp result = op.foldStamp(v1);
            checkUnaryOperation(op, result, boundaryStamp(v1, false));
            checkUnaryOperation(op, result, boundaryStamp(v1, true));
        }
    }

    private static void checkUnaryOperation(ArithmeticOpTable.UnaryOp<?> op, Stamp result, Stamp v1stamp) {
        Stamp folded = op.foldStamp(v1stamp);
        Constant v1constant = v1stamp.asConstant();
        if (v1constant != null) {
            Constant constant = op.foldConstant(v1constant);
            if (constant != null) {
                Constant constant2 = folded.asConstant();
                if (constant2 == null && v1stamp instanceof FloatStamp) {
                    JavaConstant c = (JavaConstant) constant;
                    assertTrue((c.getJavaKind() == JavaKind.Double && Double.isNaN(c.asDouble())) ||
                                    (c.getJavaKind() == JavaKind.Float && Float.isNaN(c.asFloat())));
                } else {
                    assertTrue(constant2 != null, "should constant fold %s %s %s", op, v1stamp, folded);
                    assertTrue(constant.equals(constant2), "should produce same constant %s %s %s %s", op, v1stamp, constant, constant2);
                }
            }
        } else {
            assertTrue(v1stamp.isEmpty() || v1stamp instanceof FloatStamp);
        }
        assertTrue(result.meet(folded).equals(result), "result out of range %s %s %s %s %s", op, v1stamp, folded, result, result.meet(folded));
    }
}
