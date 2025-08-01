/*
 * Copyright (c) 2021, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.strings;

import static com.oracle.truffle.api.CompilerDirectives.isPartialEvaluationConstant;
import static com.oracle.truffle.api.strings.TStringGuards.bigEndian;
import static com.oracle.truffle.api.strings.TStringGuards.indexOfCannotMatch;
import static com.oracle.truffle.api.strings.TStringGuards.is7BitCompatible;
import static com.oracle.truffle.api.strings.TStringGuards.is7Or8Bit;
import static com.oracle.truffle.api.strings.TStringGuards.is8BitCompatible;
import static com.oracle.truffle.api.strings.TStringGuards.isAscii;
import static com.oracle.truffle.api.strings.TStringGuards.isBroken;
import static com.oracle.truffle.api.strings.TStringGuards.isBrokenMultiByte;
import static com.oracle.truffle.api.strings.TStringGuards.isBytes;
import static com.oracle.truffle.api.strings.TStringGuards.isFixedWidth;
import static com.oracle.truffle.api.strings.TStringGuards.isInlinedJavaString;
import static com.oracle.truffle.api.strings.TStringGuards.isLatin1;
import static com.oracle.truffle.api.strings.TStringGuards.isStride0;
import static com.oracle.truffle.api.strings.TStringGuards.isStride1;
import static com.oracle.truffle.api.strings.TStringGuards.isSupportedEncoding;
import static com.oracle.truffle.api.strings.TStringGuards.isSupportedEncodingWithCompaction;
import static com.oracle.truffle.api.strings.TStringGuards.isUTF16;
import static com.oracle.truffle.api.strings.TStringGuards.isUTF16FE;
import static com.oracle.truffle.api.strings.TStringGuards.isUTF16Or32;
import static com.oracle.truffle.api.strings.TStringGuards.isUTF32;
import static com.oracle.truffle.api.strings.TStringGuards.isUTF32FE;
import static com.oracle.truffle.api.strings.TStringGuards.isUTF8;
import static com.oracle.truffle.api.strings.TStringGuards.isUnsupportedEncoding;
import static com.oracle.truffle.api.strings.TStringGuards.littleEndian;
import static com.oracle.truffle.api.strings.TStringUnsafe.byteArrayBaseOffset;
import static com.oracle.truffle.api.strings.TStringUnsafe.charArrayBaseOffset;
import static com.oracle.truffle.api.strings.TStringUnsafe.intArrayBaseOffset;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.Reference;
import java.util.Arrays;
import java.util.BitSet;

import org.graalvm.collections.EconomicSet;
import org.graalvm.collections.Equivalence;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Exclusive;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import com.oracle.truffle.api.profiles.InlinedConditionProfile;
import com.oracle.truffle.api.profiles.InlinedIntValueProfile;
import com.oracle.truffle.api.strings.TStringInternalNodes.ToIndexableNode;
import com.oracle.truffle.api.strings.TStringInternalNodesFactory.CalcStringAttributesNodeGen;

/**
 * Represents a primitive String type, which can be reused across languages. Language implementers
 * are encouraged to use Truffle Strings as their language's string type for easier interoperability
 * and better performance. Truffle strings can be encoded in a number of {@link Encoding encodings}.
 * A {@link TruffleString} object can cache multiple representations (in multiple encodings) of the
 * same string in the string object itself. A single {@link TruffleString} instance can also
 * represent the same string in multiple encodings, if the string's content would be equal in all
 * such encodings (e.g. a string containing only ASCII characters can be viewed as being encoded in
 * almost any encoding, since the encoded bytes would be equal). To facilitate this, all methods
 * have an {@code expectedEncoding} parameter to indicate which encoding a given string should be
 * viewed in.
 * <p>
 * {@link TruffleString} instances can be created via one of the following nodes, or via
 * {@link TruffleStringBuilder}.
 * <ul>
 * <li>{@link FromByteArrayNode}</li>
 * <li>{@link FromCharArrayUTF16Node}</li>
 * <li>{@link FromJavaStringNode}</li>
 * <li>{@link FromIntArrayUTF32Node}</li>
 * <li>{@link FromNativePointerNode}</li>
 * <li>{@link FromCodePointNode}</li>
 * <li>{@link FromLongNode}</li>
 * </ul>
 *
 * For iteration use {@link TruffleStringIterator}. There is a version of {@link TruffleString} that
 * is also mutable. See {@link MutableTruffleString} for details.
 * <p>
 * Please see the
 * <a href="https://github.com/oracle/graal/tree/master/truffle/docs/TruffleStrings.md">tutorial</a>
 * for further usage instructions.
 *
 * @since 22.1
 */
public final class TruffleString extends AbstractTruffleString {
    static final Object ETERNAL_POINTER = new Object();
    private static final VarHandle NEXT_UPDATER = initializeNextUpdater();

    @TruffleBoundary
    private static VarHandle initializeNextUpdater() {
        try {
            return MethodHandles.lookup().findVarHandle(TruffleString.class, "next", TruffleString.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static final byte FLAG_CACHE_HEAD = (byte) 0x80;
    TruffleString next;

    private TruffleString(Object data, int offset, int length, int stride, Encoding encoding, int codePointLength, int codeRange, int hashCode, boolean isCacheHead) {
        super(data, offset, length, stride, encoding, isCacheHead ? FLAG_CACHE_HEAD : 0, codePointLength, codeRange, hashCode);
    }

    private TruffleString(Object data, int offset, int length, int stride, Encoding encoding, int codePointLength, int codeRange, int hashCode, boolean isCacheHead, TruffleString cacheEntry) {
        this(data, offset, length, stride, encoding, codePointLength, codeRange, hashCode, isCacheHead);
        if (cacheEntry != null) {
            assert !cacheEntry.isCacheHead();
            assert isCacheHead();
            assert next == null;
            TruffleString cacheHead = this;
            cacheEntry.next = cacheHead;
            cacheHead.next = cacheEntry;
        }
    }

    private static TruffleString create(Object data, int offset, int length, int stride, Encoding encoding, int codePointLength, int codeRange, int hashCode, boolean isCacheHead) {
        TruffleString string = new TruffleString(data, offset, length, stride, encoding, codePointLength, codeRange, hashCode, isCacheHead);
        if (AbstractTruffleString.DEBUG_ALWAYS_CREATE_JAVA_STRING) {
            string.toJavaStringUncached();
        }
        return string;
    }

    private static TruffleString createWithCacheEntry(Object data, int offset, int length, int stride, Encoding encoding, int codePointLength, int codeRange, int hashCode, TruffleString cacheEntry) {
        TruffleString string = new TruffleString(data, offset, length, stride, encoding, codePointLength, codeRange, hashCode, true, cacheEntry);
        if (AbstractTruffleString.DEBUG_ALWAYS_CREATE_JAVA_STRING) {
            string.toJavaStringUncached();
        }
        return string;
    }

    static TruffleString createFromByteArray(byte[] bytes, int length, int stride, Encoding encoding, int codePointLength, int codeRange) {
        return createFromByteArray(bytes, 0, length, stride, encoding, codePointLength, codeRange, true);
    }

    static TruffleString createFromByteArray(byte[] bytes, int length, int stride, Encoding encoding, int codePointLength, int codeRange, boolean isCacheHead) {
        return createFromByteArray(bytes, 0, length, stride, encoding, codePointLength, codeRange, 0, isCacheHead);
    }

    static TruffleString createFromByteArray(byte[] bytes, int offset, int length, int stride, Encoding encoding, int codePointLength, int codeRange, boolean isCacheHead) {
        return createFromByteArray(bytes, offset, length, stride, encoding, codePointLength, codeRange, 0, isCacheHead);
    }

    static TruffleString createFromByteArray(byte[] bytes, int offset, int length, int stride, Encoding encoding, int codePointLength, int codeRange, int hashCode, boolean isCacheHead) {
        return createFromArray(bytes, offset, length, stride, encoding, codePointLength, codeRange, hashCode, isCacheHead);
    }

    static TruffleString createFromArray(Object bytes, int offset, int length, int stride, Encoding encoding, int codePointLength, int codeRange) {
        return createFromArray(bytes, offset, length, stride, encoding, codePointLength, codeRange, true);
    }

    static TruffleString createFromArray(Object bytes, int offset, int length, int stride, Encoding encoding, int codePointLength, int codeRange, boolean isCacheHead) {
        return createFromArray(bytes, offset, length, stride, encoding, codePointLength, codeRange, 0, isCacheHead);
    }

    static TruffleString createFromArray(Object bytes, int offset, int length, int stride, Encoding encoding, int codePointLength, int codeRange, int hashCode, boolean isCacheHead) {
        assert bytes instanceof byte[] || isInlinedJavaString(bytes) || bytes instanceof NativePointer;
        assert offset >= 0;
        assert bytes instanceof NativePointer || offset + ((long) length << stride) <= ((byte[]) bytes).length;
        assert attrsAreCorrect(bytes, encoding, offset, length, codePointLength, codeRange, stride);

        if (DEBUG_NON_ZERO_OFFSET && bytes instanceof byte[]) {
            int byteLength = Math.toIntExact((long) length << stride);
            int add = byteLength;
            byte[] copy = new byte[add + byteLength];
            System.arraycopy(bytes, offset, copy, add, byteLength);
            return TruffleString.create(copy, add, length, stride, encoding, codePointLength, codeRange, hashCode, isCacheHead);
        }
        return TruffleString.create(bytes, offset, length, stride, encoding, codePointLength, codeRange, hashCode, isCacheHead);
    }

    static TruffleString createFromByteArrayWithCacheEntry(byte[] bytes, int offset, int length, int stride, Encoding encoding, int codePointLength, int codeRange, int hashCode,
                    TruffleString cacheEntry) {
        assert offset >= 0;
        assert offset + ((long) length << stride) <= bytes.length;
        assert attrsAreCorrect(bytes, encoding, offset, length, codePointLength, codeRange, stride);
        return TruffleString.createWithCacheEntry(bytes, offset, length, stride, encoding, codePointLength, codeRange, hashCode, cacheEntry);
    }

    static TruffleString createConstant(byte[] bytes, int length, int stride, Encoding encoding, int codePointLength, int codeRange) {
        return createConstant(bytes, length, stride, encoding, codePointLength, codeRange, true);
    }

    static TruffleString createConstant(byte[] bytes, int length, int stride, Encoding encoding, int codePointLength, int codeRange, boolean isCacheHead) {
        TruffleString ret = createFromByteArray(bytes, length, stride, encoding, codePointLength, codeRange, isCacheHead);
        // eagerly compute cached hash
        ret.hashCode();
        return ret;
    }

    static TruffleString createLazyLong(long value, Encoding encoding) {
        int length = NumberConversion.stringLengthLong(value);
        int hash = HashCodeNode.maskZero(NumberConversion.computeLongStringHashCode(value));
        return TruffleString.create(new LazyLong(value), 0, length, 0, encoding, length, TSCodeRange.get7Bit(), hash, true);
    }

    static TruffleString createLazyConcat(TruffleString a, TruffleString b, Encoding encoding, int length, int stride, int codeRange) {
        assert !TSCodeRange.isBrokenMultiByte(a.codeRange());
        assert !TSCodeRange.isBrokenMultiByte(b.codeRange());
        assert a.isLooselyCompatibleTo(encoding);
        assert b.isLooselyCompatibleTo(encoding);
        assert length == a.length() + b.length();
        return TruffleString.create(new LazyConcat(a, b), 0, length, stride, encoding, a.codePointLength() + b.codePointLength(), codeRange, 0, true);
    }

    static TruffleString createWrapJavaString(String str, int codePointLength, int codeRange) {
        int stride = TStringUnsafe.getJavaStringStride(str);
        int hash = TStringUnsafe.getJavaStringHashMasked(str);
        return TruffleString.create(str, 0, str.length(), stride, Encoding.UTF_16, codePointLength, codeRange, hash, false);
    }

    private static boolean attrsAreCorrect(Object dataA, Encoding encoding, int offset, int length, int codePointLength, int codeRange, int stride) {
        CompilerAsserts.neverPartOfCompilation();
        int knownCodeRange = TSCodeRange.getUnknownCodeRangeForEncoding(encoding.id);
        if (isUTF16Or32(encoding) && stride == 0) {
            knownCodeRange = TSCodeRange.get8Bit();
        } else if (isUTF32(encoding) && stride == 1) {
            knownCodeRange = TSCodeRange.get16Bit();
        }
        assert dataA instanceof NativePointer || stride == Stride.fromCodeRangeAllowImprecise(codeRange, encoding);
        try {
            final byte[] arrayA;
            final long addOffsetA;
            if (dataA instanceof byte[]) {
                arrayA = (byte[]) dataA;
                addOffsetA = byteArrayBaseOffset();
            } else {
                arrayA = null;
                addOffsetA = NativePointer.unwrap(dataA);
            }
            final long offsetA = offset + addOffsetA;
            long attrs = CalcStringAttributesNodeGen.getUncached().execute(CalcStringAttributesNodeGen.getUncached(), null, arrayA, offsetA, length, stride, encoding, 0, knownCodeRange);
            int cpLengthCalc = StringAttributes.getCodePointLength(attrs);
            int codeRangeCalc = StringAttributes.getCodeRange(attrs);
            assert codePointLength == -1 || cpLengthCalc == codePointLength : "inconsistent codePointLength: " + cpLengthCalc + " != " + codePointLength;
            if (TSCodeRange.isPrecise(codeRange)) {
                assert codeRangeCalc == codeRange : "inconsistent codeRange: " + TSCodeRange.toString(codeRangeCalc) + " != " + TSCodeRange.toString(codeRange);
            } else {
                assert TSCodeRange.isMoreRestrictiveOrEqual(codeRangeCalc, codeRange) : "imprecise codeRange more restrictive than actual codeRange: " + TSCodeRange.toString(codeRangeCalc) + " > " +
                                TSCodeRange.toString(codeRange);
            }
            return true;
        } finally {
            Reference.reachabilityFence(dataA);
        }
    }

    boolean isCacheHead() {
        assert ((flags() & FLAG_CACHE_HEAD) != 0) == (flags() < 0);
        return flags() < 0;
    }

    TruffleString getCacheHead() {
        assert cacheRingIsValid();
        TruffleString cur = next;
        if (cur == null) {
            assert isCacheHead();
            return this;
        }
        while (!cur.isCacheHead()) {
            cur = cur.next;
        }
        return cur;
    }

    @TruffleBoundary
    void cacheInsert(TruffleString entry) {
        assert !entry.isCacheHead();
        // the cache head does never change
        TruffleString cacheHead = getCacheHead();
        assert !cacheEntryEquals(cacheHead, entry);
        TruffleString cacheHeadNext;
        do {
            cacheHeadNext = cacheHead.next;
            if (hasDuplicateEncoding(cacheHead, cacheHeadNext, entry)) {
                return;
            }
            entry.next = cacheHeadNext == null ? cacheHead : cacheHeadNext;
        } while (!setNextAtomic(cacheHead, cacheHeadNext, entry));
    }

    private static boolean hasDuplicateEncoding(TruffleString cacheHead, TruffleString start, TruffleString insertEntry) {
        if (start == null) {
            return false;
        }
        TruffleString current = start;
        while (current != cacheHead) {
            if (cacheEntryEquals(insertEntry, current)) {
                return true;
            }
            current = current.next;
        }
        return false;
    }

    private static boolean cacheEntryEquals(TruffleString a, TruffleString b) {
        return b.encoding() == a.encoding() && a.isNative() == b.isNative() && a.stride() == b.stride() && (!isUTF16(a.encoding()) || b.isJavaString() == a.isJavaString());
    }

    @TruffleBoundary
    private static boolean setNextAtomic(TruffleString cacheHead, TruffleString currentNext, TruffleString newNext) {
        return NEXT_UPDATER.compareAndSet(cacheHead, currentNext, newNext);
    }

    private boolean cacheRingIsValid() {
        CompilerAsserts.neverPartOfCompilation();
        TruffleString head = null;
        TruffleString cur = this;
        boolean javaStringVisited = false;
        BitSet visitedManaged = new BitSet(Encoding.values().length);
        BitSet visitedNativeRegular = new BitSet(Encoding.values().length);
        BitSet visitedNativeCompact = new BitSet(Encoding.values().length);
        EconomicSet<TruffleString> visited = EconomicSet.create(Equivalence.IDENTITY_WITH_SYSTEM_HASHCODE);
        do {
            if (cur.isCacheHead()) {
                assert head == null : "multiple cache heads";
                head = cur;
            }
            if (cur.isJavaString()) {
                assert !javaStringVisited : "duplicate cached java string";
                javaStringVisited = true;
            } else {
                Encoding encoding = Encoding.get(cur.encoding());
                if (cur.isManaged()) {
                    assert !visitedManaged.get(cur.encoding()) : "duplicate managed " + encoding;
                    visitedManaged.set(cur.encoding());
                } else {
                    if (cur.stride() == encoding.naturalStride) {
                        assert !visitedNativeRegular.get(cur.encoding()) : "duplicate native " + encoding;
                        visitedNativeRegular.set(cur.encoding());
                    } else {
                        assert !visitedNativeCompact.get(cur.encoding()) : "duplicate compact native " + encoding;
                        visitedNativeCompact.set(cur.encoding());
                    }
                }
            }
            assert visited.add(cur) : "not a ring structure";
            cur = cur.next;
        } while (cur != this && cur != null);
        return true;
    }

    /**
     * The list of encodings supported by {@link TruffleString}. {@link TruffleString} is especially
     * optimized for the following encodings:
     * <ul>
     * <li>{@code UTF-32}: this means UTF-32 <i>in your system's endianness</i>.
     * {@link TruffleString} transparently compacts UTF-32 strings to 8-bit or 16-bit
     * representations, where possible.</li>
     * <li>{@code UTF-16}: this means UTF-16 <i>in your system's endianness</i>.
     * {@link TruffleString} transparently compacts UTF-16 strings to 8-bit representations, where
     * possible.</li>
     * <li>{@code UTF-8}</li>
     * <li>{@code ISO-8859-1}</li>
     * <li>{@code US-ASCII}</li>
     * <li>{@code BYTES}, which is essentially identical to US-ASCII, with the only difference being
     * that {@code BYTES} treats all byte values as valid codepoints.</li>
     * </ul>
     * <p>
     * </p>
     * All other encodings are supported using the JRuby JCodings library, which incurs more
     * {@link TruffleBoundary} calls. NOTE: to enable support for these encodings,
     * {@code TruffleLanguage.Registration#needsAllEncodings()} must be set to {@code true} in the
     * truffle language's registration.
     *
     * @since 22.1
     */
    public enum Encoding {

        /* directly supported encodings */
        /**
         * UTF-32LE. Directly supported if the current system is little-endian.
         *
         * @since 22.1
         */
        UTF_32LE(littleEndian() ? 0 : 99, "UTF-32LE", littleEndian() ? 2 : 0, littleEndian()),
        /**
         * UTF-32BE. Directly supported if the current system is big-endian.
         *
         * @since 22.1
         */
        UTF_32BE(littleEndian() ? 99 : 0, "UTF-32BE", littleEndian() ? 0 : 2, bigEndian()),
        /**
         * UTF-16LE. Directly supported if the current system is little-endian.
         *
         * @since 22.1
         */
        UTF_16LE(littleEndian() ? 1 : 100, "UTF-16LE", littleEndian() ? 1 : 0, false),
        /**
         * UTF-16BE. Directly supported if the current system is big-endian.
         *
         * @since 22.1
         */
        UTF_16BE(littleEndian() ? 100 : 1, "UTF-16BE", littleEndian() ? 0 : 1, false),
        /**
         * ISO-8859-1, also known as LATIN-1, which is equivalent to US-ASCII + the LATIN-1
         * Supplement Unicode block.
         *
         * @since 22.1
         */
        ISO_8859_1(2, "ISO-8859-1", 0, true),
        /**
         * UTF-8.
         *
         * @since 22.1
         */
        UTF_8(3, "UTF-8", 0, false),
        /**
         * US-ASCII, which maps only 7-bit characters.
         *
         * @since 22.1
         */
        US_ASCII(4, "US-ASCII", 0, true),
        /**
         * Special "encoding" BYTES: This encoding is identical to US-ASCII, but treats all values
         * outside the us-ascii range as valid codepoints as well. Caution: no codepoint mappings
         * are defined for non-us-ascii values in this encoding, so {@link SwitchEncodingNode} will
         * replace all of them with {@code '?'} when converting from or to BYTES! To preserve all
         * bytes and "reinterpret" a BYTES string in another encoding, use
         * {@link ForceEncodingNode}.
         *
         * @since 22.1
         */
        BYTES(5, "ASCII-8BIT", 0, true),

        /* encodings supported by falling back to JCodings */

        /**
         * Big5.
         *
         * @since 22.1
         */
        Big5(6, "Big5", false),
        /**
         * Big5-HKSCS.
         *
         * @since 22.1
         */
        Big5_HKSCS(7, "Big5-HKSCS", false),
        /**
         * Big5-UAO.
         *
         * @since 22.1
         */
        Big5_UAO(8, "Big5-UAO", false),
        /**
         * CESU-8.
         *
         * @since 23.0
         */
        CESU_8(9, "CESU-8", false),
        /**
         * CP51932.
         *
         * @since 22.1
         */
        CP51932(10, "CP51932", false),
        /**
         * CP850.
         *
         * @since 22.1
         */
        CP850(11, "CP850", true),
        /**
         * CP852.
         *
         * @since 22.1
         */
        CP852(12, "CP852", true),
        /**
         * CP855.
         *
         * @since 22.1
         */
        CP855(13, "CP855", true),
        /**
         * CP949.
         *
         * @since 22.1
         */
        CP949(14, "CP949", false),
        /**
         * CP950.
         *
         * @since 22.1
         */
        CP950(15, "CP950", false),
        /**
         * CP951.
         *
         * @since 22.1
         */
        CP951(16, "CP951", false),
        /**
         * EUC-JIS-2004.
         *
         * @since 22.1
         */
        EUC_JIS_2004(17, "EUC-JIS-2004", false),
        /**
         * EUC-JP.
         *
         * @since 22.1
         */
        EUC_JP(18, "EUC-JP", false),
        /**
         * EUC-KR.
         *
         * @since 22.1
         */
        EUC_KR(19, "EUC-KR", false),
        /**
         * EUC-TW.
         *
         * @since 22.1
         */
        EUC_TW(20, "EUC-TW", false),
        /**
         * Emacs-Mule.
         *
         * @since 22.1
         */
        Emacs_Mule(21, "Emacs-Mule", false),
        /**
         * EucJP-ms.
         *
         * @since 22.1
         */
        EucJP_ms(22, "eucJP-ms", false),
        /**
         * GB12345.
         *
         * @since 22.1
         */
        GB12345(23, "GB12345", false),
        /**
         * GB18030.
         *
         * @since 22.1
         */
        GB18030(24, "GB18030", false),
        /**
         * GB1988.
         *
         * @since 22.1
         */
        GB1988(25, "GB1988", true),
        /**
         * GB2312.
         *
         * @since 22.1
         */
        GB2312(26, "GB2312", false),
        /**
         * GBK.
         *
         * @since 22.1
         */
        GBK(27, "GBK", false),
        /**
         * IBM437.
         *
         * @since 22.1
         */
        IBM437(28, "IBM437", true),
        /**
         * IBM720.
         *
         * @since 23.0
         */
        IBM720(29, "IBM720", true),
        /**
         * IBM737.
         *
         * @since 22.1
         */
        IBM737(30, "IBM737", true),
        /**
         * IBM775.
         *
         * @since 22.1
         */
        IBM775(31, "IBM775", true),
        /**
         * IBM852.
         *
         * @since 22.1
         */
        IBM852(32, "IBM852", true),
        /**
         * IBM855.
         *
         * @since 22.1
         */
        IBM855(33, "IBM855", true),
        /**
         * IBM857.
         *
         * @since 22.1
         */
        IBM857(34, "IBM857", true),
        /**
         * IBM860.
         *
         * @since 22.1
         */
        IBM860(35, "IBM860", true),
        /**
         * IBM861.
         *
         * @since 22.1
         */
        IBM861(36, "IBM861", true),
        /**
         * IBM862.
         *
         * @since 22.1
         */
        IBM862(37, "IBM862", true),
        /**
         * IBM863.
         *
         * @since 22.1
         */
        IBM863(38, "IBM863", true),
        /**
         * IBM864.
         *
         * @since 22.1
         */
        IBM864(39, "IBM864", true),
        /**
         * IBM865.
         *
         * @since 22.1
         */
        IBM865(40, "IBM865", true),
        /**
         * IBM866.
         *
         * @since 22.1
         */
        IBM866(41, "IBM866", true),
        /**
         * IBM869.
         *
         * @since 22.1
         */
        IBM869(42, "IBM869", true),
        /**
         * ISO-8859-10.
         *
         * @since 22.1
         */
        ISO_8859_10(43, "ISO-8859-10", true),
        /**
         * ISO-8859-11.
         *
         * @since 22.1
         */
        ISO_8859_11(44, "ISO-8859-11", true),
        /**
         * ISO-8859-13.
         *
         * @since 22.1
         */
        ISO_8859_13(45, "ISO-8859-13", true),
        /**
         * ISO-8859-14.
         *
         * @since 22.1
         */
        ISO_8859_14(46, "ISO-8859-14", true),
        /**
         * ISO-8859-15.
         *
         * @since 22.1
         */
        ISO_8859_15(47, "ISO-8859-15", true),
        /**
         * ISO-8859-16.
         *
         * @since 22.1
         */
        ISO_8859_16(48, "ISO-8859-16", true),
        /**
         * ISO-8859-2.
         *
         * @since 22.1
         */
        ISO_8859_2(49, "ISO-8859-2", true),
        /**
         * ISO-8859-3.
         *
         * @since 22.1
         */
        ISO_8859_3(50, "ISO-8859-3", true),
        /**
         * ISO-8859-4.
         *
         * @since 22.1
         */
        ISO_8859_4(51, "ISO-8859-4", true),
        /**
         * ISO-8859-5.
         *
         * @since 22.1
         */
        ISO_8859_5(52, "ISO-8859-5", true),
        /**
         * ISO-8859-6.
         *
         * @since 22.1
         */
        ISO_8859_6(53, "ISO-8859-6", true),
        /**
         * ISO-8859-7.
         *
         * @since 22.1
         */
        ISO_8859_7(54, "ISO-8859-7", true),
        /**
         * ISO-8859-8.
         *
         * @since 22.1
         */
        ISO_8859_8(55, "ISO-8859-8", true),
        /**
         * ISO-8859-9.
         *
         * @since 22.1
         */
        ISO_8859_9(56, "ISO-8859-9", true),
        /**
         * KOI8-R.
         *
         * @since 22.1
         */
        KOI8_R(57, "KOI8-R", true),
        /**
         * KOI8-U.
         *
         * @since 22.1
         */
        KOI8_U(58, "KOI8-U", true),
        /**
         * MacCentEuro.
         *
         * @since 22.1
         */
        MacCentEuro(59, "macCentEuro", true),
        /**
         * MacCroatian.
         *
         * @since 22.1
         */
        MacCroatian(60, "macCroatian", true),
        /**
         * MacCyrillic.
         *
         * @since 22.1
         */
        MacCyrillic(61, "macCyrillic", true),
        /**
         * MacGreek.
         *
         * @since 22.1
         */
        MacGreek(62, "macGreek", true),
        /**
         * MacIceland.
         *
         * @since 22.1
         */
        MacIceland(63, "macIceland", true),
        /**
         * MacJapanese.
         *
         * @since 22.1
         */
        MacJapanese(64, "MacJapanese", false),
        /**
         * MacRoman.
         *
         * @since 22.1
         */
        MacRoman(65, "macRoman", true),
        /**
         * MacRomania.
         *
         * @since 22.1
         */
        MacRomania(66, "macRomania", true),
        /**
         * MacThai.
         *
         * @since 22.1
         */
        MacThai(67, "macThai", true),
        /**
         * MacTurkish.
         *
         * @since 22.1
         */
        MacTurkish(68, "macTurkish", true),
        /**
         * MacUkraine.
         *
         * @since 22.1
         */
        MacUkraine(69, "macUkraine", true),
        /**
         * SJIS-DoCoMo.
         *
         * @since 22.1
         */
        SJIS_DoCoMo(70, "SJIS-DoCoMo", false),
        /**
         * SJIS-KDDI.
         *
         * @since 22.1
         */
        SJIS_KDDI(71, "SJIS-KDDI", false),
        /**
         * SJIS-SoftBank.
         *
         * @since 22.1
         */
        SJIS_SoftBank(72, "SJIS-SoftBank", false),
        /**
         * Shift-JIS.
         *
         * @since 22.1
         */
        Shift_JIS(73, "Shift_JIS", false),
        /**
         * Stateless-ISO-2022-JP.
         *
         * @since 22.1
         */
        Stateless_ISO_2022_JP(74, "stateless-ISO-2022-JP", false),
        /**
         * Stateless-ISO-2022-JP-KDDI.
         *
         * @since 22.1
         */
        Stateless_ISO_2022_JP_KDDI(75, "stateless-ISO-2022-JP-KDDI", false),
        /**
         * TIS-620.
         *
         * @since 22.1
         */
        TIS_620(76, "TIS-620", true),
        /**
         * UTF8-DoCoMo.
         *
         * @since 22.1
         */
        UTF8_DoCoMo(77, "UTF8-DoCoMo", false),
        /**
         * UTF8-KDDI.
         *
         * @since 22.1
         */
        UTF8_KDDI(78, "UTF8-KDDI", false),
        /**
         * UTF8-MAC.
         *
         * @since 22.1
         */
        UTF8_MAC(79, "UTF8-MAC", false),
        /**
         * UTF8-SoftBank.
         *
         * @since 22.1
         */
        UTF8_SoftBank(80, "UTF8-SoftBank", false),
        /**
         * Windows-1250.
         *
         * @since 22.1
         */
        Windows_1250(81, "Windows-1250", true),
        /**
         * Windows-1251.
         *
         * @since 22.1
         */
        Windows_1251(82, "Windows-1251", true),
        /**
         * Windows-1252.
         *
         * @since 22.1
         */
        Windows_1252(83, "Windows-1252", true),
        /**
         * Windows-1253.
         *
         * @since 22.1
         */
        Windows_1253(84, "Windows-1253", true),
        /**
         * Windows-1254.
         *
         * @since 22.1
         */
        Windows_1254(85, "Windows-1254", true),
        /**
         * Windows-1255.
         *
         * @since 22.1
         */
        Windows_1255(86, "Windows-1255", true),
        /**
         * Windows-1256.
         *
         * @since 22.1
         */
        Windows_1256(87, "Windows-1256", true),
        /**
         * Windows-1257.
         *
         * @since 22.1
         */
        Windows_1257(88, "Windows-1257", true),
        /**
         * Windows-1258.
         *
         * @since 22.1
         */
        Windows_1258(89, "Windows-1258", true),
        /**
         * Windows-31J.
         *
         * @since 22.1
         */
        Windows_31J(90, "Windows-31J", false),
        /**
         * Windows-874.
         *
         * @since 22.1
         */
        Windows_874(91, "Windows-874", true),
        /* non-ascii-compatible encodings */
        /**
         * CP50220.
         *
         * @since 22.1
         */
        CP50220(92, "CP50220", true),
        /**
         * CP50221.
         *
         * @since 22.1
         */
        CP50221(93, "CP50221", true),
        /**
         * IBM037.
         *
         * @since 22.1
         */
        IBM037(94, "IBM037", true),
        /**
         * ISO-2022-JP.
         *
         * @since 22.1
         */
        ISO_2022_JP(95, "ISO-2022-JP", true),
        /**
         * ISO-2022-JP-2.
         *
         * @since 22.1
         */
        ISO_2022_JP_2(96, "ISO-2022-JP-2", true),
        /**
         * ISO-2022-JP-KDDI.
         *
         * @since 22.1
         */
        ISO_2022_JP_KDDI(97, "ISO-2022-JP-KDDI", true),
        /**
         * UTF-7.
         *
         * @since 22.1
         */
        UTF_7(98, "UTF-7", true);

        /**
         * UTF-32 in <i>the current system's endianness</i>, without byte-order mark, with
         * transparent string compaction.
         *
         * @since 22.1
         */
        public static final Encoding UTF_32 = littleEndian() ? UTF_32LE : UTF_32BE;
        /**
         * UTF-16 in <i>the current system's endianness</i>, without byte-order mark, with
         * transparent string compaction.
         *
         * @since 22.1
         */
        public static final Encoding UTF_16 = littleEndian() ? UTF_16LE : UTF_16BE;

        static final Encoding UTF_32_FOREIGN_ENDIAN = littleEndian() ? UTF_32BE : UTF_32LE;
        static final Encoding UTF_16_FOREIGN_ENDIAN = littleEndian() ? UTF_16BE : UTF_16LE;

        final byte id;
        final String jCodingName;
        final byte maxCompatibleCodeRange;
        final byte naturalStride;
        final boolean fixedWidth;

        /**
         * Constructor for exotic encodings (from JCodings).
         */
        Encoding(int id, String jCodingName, boolean fixedWidth) {
            this(id, jCodingName, 0, fixedWidth);
        }

        /**
         * Constructor for well-known encodings.
         */
        Encoding(int id, String jCodingName, int naturalStride, boolean fixedWidth) {
            assert id <= 0x7f : id;
            assert Stride.isStride(naturalStride) : naturalStride;
            this.id = (byte) id;
            this.jCodingName = jCodingName;
            if (is16BitCompatible()) {
                maxCompatibleCodeRange = (byte) (TSCodeRange.get16Bit() + 1);
            } else if (is8BitCompatible()) {
                maxCompatibleCodeRange = (byte) (TSCodeRange.get8Bit() + 1);
            } else if (is7BitCompatible()) {
                maxCompatibleCodeRange = (byte) (TSCodeRange.get7Bit() + 1);
            } else {
                maxCompatibleCodeRange = 0;
            }
            this.naturalStride = (byte) naturalStride;
            this.fixedWidth = fixedWidth;
        }

        @CompilationFinal(dimensions = 1) static final byte[] EMPTY_BYTES = new byte[0];
        @CompilationFinal(dimensions = 1) private static final Encoding[] ENCODINGS_TABLE;
        @CompilationFinal(dimensions = 1) private static final byte[] MAX_COMPATIBLE_CODE_RANGE;
        @CompilationFinal(dimensions = 1) private static final TruffleString[] EMPTY_STRINGS;

        static {
            final Encoding[] encodingValues = Encoding.values();
            ENCODINGS_TABLE = new Encoding[encodingValues.length];
            MAX_COMPATIBLE_CODE_RANGE = new byte[encodingValues.length];

            for (Encoding e : encodingValues) {
                assert ENCODINGS_TABLE[e.id] == null;
                ENCODINGS_TABLE[e.id] = e;
                MAX_COMPATIBLE_CODE_RANGE[e.id] = e.maxCompatibleCodeRange;
            }

            assert UTF_16.naturalStride == 1;
            assert UTF_32.naturalStride == 2;
            EMPTY_STRINGS = new TruffleString[encodingValues.length];
            EMPTY_STRINGS[US_ASCII.id] = createConstant(EMPTY_BYTES, 0, 0, US_ASCII, 0, TSCodeRange.get7Bit());
            for (Encoding e : encodingValues) {
                if (e != US_ASCII) {
                    assert EMPTY_STRINGS[e.id] == null;
                    EMPTY_STRINGS[e.id] = createEmpty(e);
                }
            }
        }

        private static TruffleString createEmpty(Encoding encoding) {
            if (encoding.is7BitCompatible() && !AbstractTruffleString.DEBUG_STRICT_ENCODING_CHECKS || encoding == Encoding.US_ASCII) {
                return EMPTY_STRINGS[US_ASCII.id];
            }
            TruffleString ret = createConstant(EMPTY_BYTES, 0, 0, encoding, 0, TSCodeRange.getAsciiCodeRange(encoding), false);
            EMPTY_STRINGS[US_ASCII.id].cacheInsert(ret);
            return ret;
        }

        /**
         * Get an empty {@link TruffleString} with this encoding.
         *
         * @since 22.1
         */
        public TruffleString getEmpty() {
            TruffleString emptyString = EMPTY_STRINGS[id];
            assert emptyString != null : this;
            return emptyString;
        }

        /**
         * Get the {@link Encoding} corresponding to the given encoding name from the
         * {@code JCodings} library.
         *
         * @since 22.1
         */
        @TruffleBoundary
        public static Encoding fromJCodingName(String name) {
            Encoding encoding = JCodings.fromJCodingsName(name);
            if (encoding == null) {
                throw InternalErrors.unknownEncoding(name);
            }
            return encoding;
        }

        static Encoding get(int encoding) {
            return ENCODINGS_TABLE[encoding];
        }

        static int getMaxCompatibleCodeRange(int encoding) {
            return MAX_COMPATIBLE_CODE_RANGE[encoding];
        }

        boolean is7BitCompatible() {
            return is7BitCompatible(id);
        }

        boolean is8BitCompatible() {
            return is8BitCompatible(id);
        }

        boolean is16BitCompatible() {
            return is16BitCompatible(id);
        }

        boolean isSupported() {
            return isSupported(id);
        }

        boolean isUnsupported() {
            return isUnsupported(id);
        }

        static boolean is7BitCompatible(int encoding) {
            return encoding < 92;
        }

        static boolean is8BitCompatible(int encoding) {
            return encoding < 3;
        }

        static boolean is16BitCompatible(int encoding) {
            return encoding < 2;
        }

        static boolean isSupportedWithCompaction(int encoding) {
            return encoding < 6;
        }

        static boolean isSupported(int encoding) {
            return encoding < 6 || encoding > 98;
        }

        static boolean isUnsupported(int encoding) {
            return encoding >= 6 && encoding < 99;
        }

        boolean isFixedWidth() {
            return fixedWidth;
        }

        static boolean isFixedWidth(int encoding) {
            return get(encoding).isFixedWidth();
        }

        boolean isSingleByte() {
            return fixedWidth && naturalStride == 0;
        }
    }

    /**
     * Provides information about a string's content. All values of this enum describe a set of
     * codepoints potentially contained by a string reporting said value.
     *
     * @since 22.1
     */
    public enum CodeRange {

        /**
         * All codepoints in this string are part of the Basic Latin Unicode block, also known as
         * ASCII (0x00 - 0x7f).
         *
         * @since 22.1
         */
        ASCII,

        /**
         * All codepoints in this string are part of the ISO-8859-1 character set (0x00 - 0xff),
         * which is equivalent to the union of the Basic Latin and the Latin-1 Supplement Unicode
         * block. If this code range is {@link GetCodeRangeNode precise}, at least one codepoint is
         * outside the ASCII range (greater than 0x7f). Applicable to {@link Encoding#ISO_8859_1},
         * {@link Encoding#UTF_16} and {@link Encoding#UTF_32} only.
         *
         * @since 22.1
         */
        LATIN_1,

        /**
         * All codepoints in this string are part of the Unicode Basic Multilingual Plane (BMP) (
         * 0x0000 - 0xffff). If this code range is {@link GetCodeRangeNode precise}, at least one
         * codepoint is outside the LATIN_1 range (greater than 0xff). Applicable to
         * {@link Encoding#UTF_16} and {@link Encoding#UTF_32} only.
         *
         * @since 22.1
         */
        BMP,

        /**
         * This string is encoded correctly ({@link IsValidNode} returns {@code true}), and if this
         * code range is {@link GetCodeRangeNode precise}, at least one codepoint is outside the
         * largest other applicable code range (e.g. greater than 0x7f on {@link Encoding#UTF_8},
         * greater than 0xffff on {@link Encoding#UTF_16}).
         *
         * @since 22.1
         */
        VALID,

        /**
         * If this code range is {@link GetCodeRangeNode precise}, the string is not encoded
         * correctly ({@link IsValidNode} returns {@code false}), and contains at least one invalid
         * codepoint. {@link GetCodeRangeImpreciseNode Otherwise}, no information about the string
         * is known.
         *
         * @since 22.1
         */
        BROKEN;

        /**
         * Returns {@code true} if this set of potential codepoints is equal to or contained by
         * {@code other}.
         *
         * @since 22.1
         */
        public boolean isSubsetOf(CodeRange other) {
            return ordinal() <= other.ordinal();
        }

        /**
         * Returns {@code true} if this set of potential codepoints is equal to or contains
         * {@code other}.
         *
         * @since 22.1
         */
        public boolean isSupersetOf(CodeRange other) {
            return ordinal() >= other.ordinal();
        }

        @CompilationFinal(dimensions = 1) private static final CodeRange[] BYTE_CODE_RANGES = {
                        CodeRange.ASCII, CodeRange.VALID, CodeRange.VALID, CodeRange.VALID, CodeRange.BROKEN};

        static CodeRange get(int codeRange) {
            assert TSCodeRange.ordinal(TSCodeRange.get7Bit()) == 0;
            assert TSCodeRange.ordinal(TSCodeRange.get8Bit()) == 1;
            assert TSCodeRange.ordinal(TSCodeRange.get16Bit()) == 2;
            assert TSCodeRange.ordinal(TSCodeRange.getValidFixedWidth()) == 3;
            assert TSCodeRange.ordinal(TSCodeRange.getValidMultiByte()) == 3;
            assert TSCodeRange.ordinal(TSCodeRange.getBrokenFixedWidth()) == 4;
            assert TSCodeRange.ordinal(TSCodeRange.getBrokenMultiByte()) == 4;
            // using a switch here to make things easier for PE
            int ordinal = TSCodeRange.ordinal(codeRange);
            switch (ordinal) {
                case 0:
                    return ASCII;
                case 1:
                    return LATIN_1;
                case 2:
                    return BMP;
                case 3:
                    return VALID;
                default:
                    assert ordinal == 4;
                    return BROKEN;
            }
        }

        static CodeRange getByteCodeRange(int codeRange, Encoding encoding) {
            return TSCodeRange.is7Bit(codeRange) && isUTF16Or32(encoding) ? CodeRange.VALID : BYTE_CODE_RANGES[TSCodeRange.ordinal(codeRange)];
        }

        static boolean equals(int codeRange, CodeRange codeRangeEnum) {
            return TSCodeRange.ordinal(codeRange) == codeRangeEnum.ordinal();
        }

        static {
            assert get(TSCodeRange.get7Bit()) == CodeRange.ASCII;
            assert get(TSCodeRange.get8Bit()) == CodeRange.LATIN_1;
            assert get(TSCodeRange.get16Bit()) == CodeRange.BMP;
            assert get(TSCodeRange.getValidFixedWidth()) == CodeRange.VALID;
            assert get(TSCodeRange.getBrokenFixedWidth()) == CodeRange.BROKEN;
            assert get(TSCodeRange.getValidMultiByte()) == CodeRange.VALID;
            assert get(TSCodeRange.getBrokenMultiByte()) == CodeRange.BROKEN;
            assert equals(TSCodeRange.get7Bit(), CodeRange.ASCII);
            assert equals(TSCodeRange.get8Bit(), CodeRange.LATIN_1);
            assert equals(TSCodeRange.get16Bit(), CodeRange.BMP);
            assert equals(TSCodeRange.getValidFixedWidth(), CodeRange.VALID);
            assert equals(TSCodeRange.getBrokenFixedWidth(), CodeRange.BROKEN);
            assert equals(TSCodeRange.getValidMultiByte(), CodeRange.VALID);
            assert equals(TSCodeRange.getBrokenMultiByte(), CodeRange.BROKEN);
        }
    }

    /**
     * Extended parameter type for the operations {@link ByteIndexOfStringNode},
     * {@link LastByteIndexOfStringNode} and {@link RegionEqualByteIndexNode}. These operations can
     * optionally perform a logical OR operation when matching their string parameters against each
     * other, in the following way:
     * <p>
     * Given a parameter {@link TruffleString} {@code a} and {@link WithMask} {@code b}, region
     * equality will be checked as shown in this exemplary method:
     *
     * <pre>
     * {@code
     * boolean regionEquals(TruffleString a, int fromIndexA, TruffleString.WithMask b, int fromIndexB) {
     *     for (int i = 0; i < length; i++) {
     *         if ((readRaw(a, fromIndexA + i) | readRaw(b.mask, i)) != readRaw(b.string, fromIndexB + i)) {
     *             return false;
     *         }
     *     }
     *     return true;
     * }
     * }
     * </pre>
     *
     * @see ByteIndexOfStringNode
     * @see LastByteIndexOfStringNode
     * @see RegionEqualByteIndexNode
     * @since 22.1
     */
    public static final class WithMask {

        final AbstractTruffleString string;
        @CompilationFinal(dimensions = 1) final byte[] mask;

        WithMask(AbstractTruffleString string, byte[] mask) {
            this.string = string;
            this.mask = mask;
        }

        /**
         * Node to create a new {@link WithMask} from a string and a byte array. See
         * {@code #execute(AbstractTruffleString, byte[], Encoding)} for details.
         *
         * @since 22.1
         */
        public abstract static class CreateNode extends AbstractPublicNode {

            CreateNode() {
            }

            /**
             * Creates a new {@link WithMask} from {@code a} and {@code mask}. {@code mask.length}
             * must be equal to the string's length in bytes. Cannot be used for UTF-16 or UTF-32
             * strings.
             *
             * @since 22.1
             */
            public abstract WithMask execute(AbstractTruffleString a, byte[] mask, Encoding expectedEncoding);

            @Specialization
            WithMask doCreate(AbstractTruffleString a, byte[] mask, Encoding expectedEncoding) {
                if (expectedEncoding == Encoding.UTF_16 || expectedEncoding == Encoding.UTF_32) {
                    throw InternalErrors.illegalArgument("use a CreateUTF16Node for UTF-16, and CreateUTF32Node for UTF-32");
                }
                a.checkEncoding(expectedEncoding);
                checkMaskLength(a, mask.length);
                assert isStride0(a);
                return new WithMask(a, Arrays.copyOf(mask, mask.length));
            }

            /**
             * Create a new {@link TruffleString.WithMask.CreateNode}.
             *
             * @since 22.1
             */
            @NeverDefault
            public static TruffleString.WithMask.CreateNode create() {
                return TruffleStringFactory.WithMaskFactory.CreateNodeGen.create();
            }

            /**
             * Get the uncached version of {@link TruffleString.WithMask.CreateNode}.
             *
             * @since 22.1
             */
            public static TruffleString.WithMask.CreateNode getUncached() {
                return TruffleStringFactory.WithMaskFactory.CreateNodeGen.getUncached();
            }
        }

        /**
         * Shorthand for calling the uncached version of {@link CreateNode}.
         *
         * @since 22.1
         */
        public static WithMask createUncached(AbstractTruffleString a, byte[] mask, Encoding expectedEncoding) {
            return CreateNode.getUncached().execute(a, mask, expectedEncoding);
        }

        /**
         * Node to create a new {@link WithMask} from a UTF-16 string and a char array. See
         * {@code #execute(AbstractTruffleString, char[])} for details.
         *
         * @since 22.1
         */
        public abstract static class CreateUTF16Node extends AbstractPublicNode {

            CreateUTF16Node() {
            }

            /**
             * Creates a new {@link WithMask} from {@code a} and {@code mask}. {@code mask.length}
             * must be equal to the string's length in {@code char}s.
             *
             * @since 22.1
             */
            public abstract WithMask execute(AbstractTruffleString a, char[] mask);

            @Specialization
            WithMask doCreate(AbstractTruffleString a, char[] mask) {
                a.checkEncoding(Encoding.UTF_16);
                checkMaskLength(a, mask.length);
                byte[] maskBytes = new byte[a.length() << a.stride()];
                if (a.stride() == 0) {
                    TStringOps.arraycopyWithStrideCB(this, mask, charArrayBaseOffset(), maskBytes, byteArrayBaseOffset(), 0, mask.length);
                } else {
                    TStringOps.arraycopyWithStrideCB(this, mask, charArrayBaseOffset(), maskBytes, byteArrayBaseOffset(), 1, mask.length);
                }
                return new WithMask(a, maskBytes);
            }

            /**
             * Create a new {@link TruffleString.WithMask.CreateNode}.
             *
             * @since 22.1
             */
            @NeverDefault
            public static TruffleString.WithMask.CreateUTF16Node create() {
                return TruffleStringFactory.WithMaskFactory.CreateUTF16NodeGen.create();
            }

            /**
             * Get the uncached version of {@link TruffleString.WithMask.CreateNode}.
             *
             * @since 22.1
             */
            public static TruffleString.WithMask.CreateUTF16Node getUncached() {
                return TruffleStringFactory.WithMaskFactory.CreateUTF16NodeGen.getUncached();
            }
        }

        /**
         * Shorthand for calling the uncached version of {@link CreateUTF16Node}.
         *
         * @since 22.1
         */
        public static WithMask createUTF16Uncached(AbstractTruffleString a, char[] mask) {
            return CreateUTF16Node.getUncached().execute(a, mask);
        }

        /**
         * Node to create a new {@link WithMask} from a UTF-32 string and an int array. See
         * {@code #execute(AbstractTruffleString, int[])} for details.
         *
         * @since 22.1
         */
        public abstract static class CreateUTF32Node extends AbstractPublicNode {

            CreateUTF32Node() {
            }

            /**
             * Creates a new {@link WithMask} from {@code a} and {@code mask}. {@code mask.length}
             * must be equal to the string's length in {@code int}s.
             *
             * @since 22.1
             */
            public abstract WithMask execute(AbstractTruffleString a, int[] mask);

            @Specialization
            WithMask doCreate(AbstractTruffleString a, int[] mask) {
                a.checkEncoding(Encoding.UTF_32);
                checkMaskLength(a, mask.length);
                byte[] maskBytes = new byte[a.length() << a.stride()];
                if (a.stride() == 0) {
                    TStringOps.arraycopyWithStrideIB(this, mask, intArrayBaseOffset(), maskBytes, byteArrayBaseOffset(), 0, mask.length);
                } else if (a.stride() == 1) {
                    TStringOps.arraycopyWithStrideIB(this, mask, intArrayBaseOffset(), maskBytes, byteArrayBaseOffset(), 1, mask.length);
                } else {
                    TStringOps.arraycopyWithStrideIB(this, mask, intArrayBaseOffset(), maskBytes, byteArrayBaseOffset(), 2, mask.length);
                }
                return new WithMask(a, maskBytes);
            }

            /**
             * Create a new {@link TruffleString.WithMask.CreateNode}.
             *
             * @since 22.1
             */
            @NeverDefault
            public static TruffleString.WithMask.CreateUTF32Node create() {
                return TruffleStringFactory.WithMaskFactory.CreateUTF32NodeGen.create();
            }

            /**
             * Get the uncached version of {@link TruffleString.WithMask.CreateNode}.
             *
             * @since 22.1
             */
            public static TruffleString.WithMask.CreateUTF32Node getUncached() {
                return TruffleStringFactory.WithMaskFactory.CreateUTF32NodeGen.getUncached();
            }
        }

        /**
         * Shorthand for calling the uncached version of {@link CreateUTF32Node}.
         *
         * @since 22.1
         */
        public static WithMask createUTF32Uncached(AbstractTruffleString a, int[] mask) {
            return CreateUTF32Node.getUncached().execute(a, mask);
        }

        private static void checkMaskLength(AbstractTruffleString string, int length) {
            if (length != string.length()) {
                throw InternalErrors.illegalArgument("mask length does not match string length!");
            }
        }
    }

    /**
     * Error handling instructions for operations that return integer values, such as indices or
     * code points.
     *
     * @since 22.3
     */
    public enum ErrorHandling {

        /**
         * This mode generally means that the operation will try to determine the "most reasonable"
         * or "most useful" return value in respect to the expected encoding and the error that
         * occurred.
         *
         * For example: best-effort error handling will cause {@link CodePointAtByteIndexNode} to
         * return the value of the integer read when reading an invalid codepoint from a
         * {@link Encoding#UTF_32} string.
         *
         * @since 22.3
         */
        BEST_EFFORT(DecodingErrorHandler.DEFAULT),

        /**
         * This mode will cause a negative value to be returned in all error cases.
         *
         * For example: return-negative error handling will cause {@link CodePointAtByteIndexNode}
         * to return a negative value when reading an invalid codepoint from a
         * {@link Encoding#UTF_32} string.
         *
         * @since 22.3
         */
        RETURN_NEGATIVE(DecodingErrorHandler.RETURN_NEGATIVE);

        final DecodingErrorHandler errorHandler;

        ErrorHandling(DecodingErrorHandler errorHandler) {
            this.errorHandler = errorHandler;
        }
    }

    /**
     * Node to create a new {@link TruffleString} from a single codepoint.
     *
     * @since 22.1
     */
    public abstract static class FromCodePointNode extends AbstractPublicNode {

        FromCodePointNode() {
        }

        /**
         * Creates a new TruffleString from a given code point.
         *
         * @since 22.1
         */
        public final TruffleString execute(int codepoint, Encoding encoding) {
            return execute(codepoint, encoding, encoding == Encoding.UTF_16);
        }

        /**
         * Creates a new TruffleString from a given code point.
         *
         * If {@code allowUTF16Surrogates} is {@code true}, {@link Character#isSurrogate(char)
         * UTF-16 surrogate values} passed as {@code codepoint} will not result in a {@code null}
         * return value, but instead be encoded on a best-effort basis. This option is only
         * supported on {@link TruffleString.Encoding#UTF_16} and
         * {@link TruffleString.Encoding#UTF_32}.
         *
         * @return a new {@link TruffleString}, or {@code null} if the given codepoint is not
         *         defined in the given encoding.
         *
         * @since 22.2
         */
        public abstract TruffleString execute(int codepoint, Encoding encoding, boolean allowUTF16Surrogates);

        @Specialization
        final TruffleString fromCodePoint(int c, Encoding enc, boolean allowUTF16Surrogates,
                        @Cached InlinedConditionProfile bytesProfile,
                        @Cached InlinedConditionProfile utf8Profile,
                        @Cached InlinedConditionProfile utf16Profile,
                        @Cached InlinedConditionProfile utf16FEProfile,
                        @Cached InlinedConditionProfile utf32Profile,
                        @Cached InlinedConditionProfile utf32FEProfile,
                        @Cached InlinedConditionProfile exoticProfile,
                        @Cached InlinedConditionProfile bmpProfile,
                        @Cached InlinedBranchProfile invalidCodePoint) {
            assert !allowUTF16Surrogates || isUTF16Or32(enc) || isUTF16FE(enc) || isUTF32FE(enc) : "allowUTF16Surrogates is only supported on UTF-16 and UTF-32";
            CompilerAsserts.partialEvaluationConstant(allowUTF16Surrogates);
            if (is7BitCompatible(enc) && Integer.compareUnsigned(c, 0x7f) <= 0) {
                return TStringConstants.getSingleByteAscii(enc, c);
            }
            if (is8BitCompatible(enc) && Integer.compareUnsigned(c, 0xff) <= 0) {
                assert isSupportedEncoding(enc);
                return TStringConstants.getSingleByte(enc, c);
            }
            if (bytesProfile.profile(this, isBytes(enc))) {
                if (Integer.compareUnsigned(c, 0xff) > 0) {
                    invalidCodePoint.enter(this);
                    return null;
                }
                return TStringConstants.getSingleByte(Encoding.BYTES, c);
            }
            final byte[] bytes;
            final int length;
            final int stride;
            final int codeRange;
            if (utf8Profile.profile(this, isUTF8(enc))) {
                if (!Encodings.isValidUnicodeCodepoint(c)) {
                    invalidCodePoint.enter(this);
                    return null;
                }
                assert c > 0x7f;
                bytes = Encodings.utf8Encode(c);
                length = bytes.length;
                stride = 0;
                codeRange = TSCodeRange.getValidMultiByte();
            } else if (utf16Profile.profile(this, isUTF16(enc))) {
                if (Integer.toUnsignedLong(c) > 0x10ffff) {
                    invalidCodePoint.enter(this);
                    return null;
                }
                assert c > 0xff;
                bytes = new byte[c <= 0xffff ? 2 : 4];
                stride = 1;
                if (bmpProfile.profile(this, c <= 0xffff)) {
                    length = 1;
                    if (Encodings.isUTF16Surrogate(c)) {
                        if (allowUTF16Surrogates) {
                            codeRange = TSCodeRange.getBrokenMultiByte();
                        } else {
                            invalidCodePoint.enter(this);
                            return null;
                        }
                    } else {
                        codeRange = TSCodeRange.get16Bit();
                    }
                    TStringOps.writeToByteArrayS1(bytes, 0, c);
                } else {
                    length = 2;
                    codeRange = TSCodeRange.getValidMultiByte();
                    Encodings.utf16EncodeSurrogatePair(c, bytes, 0);
                }
            } else if (utf32Profile.profile(this, isUTF32(enc))) {
                if (Integer.toUnsignedLong(c) > 0x10ffff) {
                    invalidCodePoint.enter(this);
                    return null;
                }
                assert c > 0xff;
                if (c <= 0xffff) {
                    if (Encodings.isUTF16Surrogate(c)) {
                        if (allowUTF16Surrogates) {
                            codeRange = TSCodeRange.getBrokenFixedWidth();
                        } else {
                            invalidCodePoint.enter(this);
                            return null;
                        }
                    } else {
                        codeRange = TSCodeRange.get16Bit();
                    }
                } else {
                    codeRange = TSCodeRange.getValidFixedWidth();
                }
                final boolean compact1 = TSCodeRange.is16Bit(codeRange);
                bytes = new byte[compact1 ? 2 : 4];
                length = 1;
                if (bmpProfile.profile(this, compact1)) {
                    stride = 1;
                    TStringOps.writeToByteArrayS1(bytes, 0, c);
                } else {
                    stride = 2;
                    TStringOps.writeToByteArrayS2(bytes, 0, c);
                }
            } else if (utf16FEProfile.profile(this, isUTF16FE(enc))) {
                if (Integer.toUnsignedLong(c) > 0x10ffff) {
                    invalidCodePoint.enter(this);
                    return null;
                }
                bytes = new byte[c <= 0xffff ? 2 : 4];
                stride = 0;
                if (bmpProfile.profile(this, c <= 0xffff)) {
                    length = 2;
                    if (Encodings.isUTF16Surrogate(c)) {
                        if (allowUTF16Surrogates) {
                            codeRange = TSCodeRange.getBrokenMultiByte();
                        } else {
                            invalidCodePoint.enter(this);
                            return null;
                        }
                    } else {
                        codeRange = TSCodeRange.getValidMultiByte();
                    }
                    TStringOps.writeToByteArrayS1(bytes, 0, Character.reverseBytes((char) c));
                } else {
                    length = 4;
                    codeRange = TSCodeRange.getValidMultiByte();
                    Encodings.utf16FEEncodeSurrogatePair(c, bytes, 0);
                }
            } else if (utf32FEProfile.profile(this, isUTF32FE(enc))) {
                if (Integer.toUnsignedLong(c) > 0x10ffff) {
                    invalidCodePoint.enter(this);
                    return null;
                }
                if (Encodings.isUTF16Surrogate(c)) {
                    if (allowUTF16Surrogates) {
                        codeRange = TSCodeRange.getBrokenMultiByte();
                    } else {
                        invalidCodePoint.enter(this);
                        return null;
                    }
                } else {
                    codeRange = TSCodeRange.getValidMultiByte();
                }
                bytes = new byte[4];
                length = 4;
                stride = 0;
                TStringOps.writeToByteArrayS2(bytes, 0, Integer.reverseBytes(c));
            } else if (exoticProfile.profile(this, isUnsupportedEncoding(enc))) {
                assert !isBytes(enc);
                length = JCodings.getInstance().getCodePointLength(enc, c);
                stride = 0;
                codeRange = TSCodeRange.getValid(JCodings.getInstance().isSingleByte(enc));
                if (length < 1) {
                    invalidCodePoint.enter(this);
                    return null;
                }
                bytes = new byte[length];
                int ret = JCodings.getInstance().writeCodePoint(enc, c, bytes, 0);
                if (ret != length || JCodings.getInstance().getCodePointLength(enc, bytes, 0, length) != ret ||
                                JCodings.getInstance().readCodePoint(enc, bytes, 0, length, DecodingErrorHandler.RETURN_NEGATIVE) != c) {
                    invalidCodePoint.enter(this);
                    return null;
                }
            } else {
                assert isAscii(enc) && Integer.compareUnsigned(c, 0x7f) > 0 || (isLatin1(enc) && Integer.compareUnsigned(c, 0xff) > 0);
                invalidCodePoint.enter(this);
                return null;
            }
            return TruffleString.createFromByteArray(bytes, length, stride, enc, 1, codeRange);
        }

        /**
         * Create a new {@link FromCodePointNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static FromCodePointNode create() {
            return TruffleStringFactory.FromCodePointNodeGen.create();
        }

        /**
         * Get the uncached version of {@link FromCodePointNode}.
         *
         * @since 22.1
         */
        public static FromCodePointNode getUncached() {
            return TruffleStringFactory.FromCodePointNodeGen.getUncached();
        }
    }

    /**
     * Shorthand for calling the uncached version of {@link FromCodePointNode}.
     *
     * @since 22.1
     */
    @TruffleBoundary
    public static TruffleString fromCodePointUncached(int codepoint, Encoding encoding) {
        return FromCodePointNode.getUncached().execute(codepoint, encoding);
    }

    /**
     * Shorthand for calling the uncached version of {@link FromCodePointNode}.
     *
     * @since 22.2
     */
    @TruffleBoundary
    public static TruffleString fromCodePointUncached(int codepoint, Encoding encoding, boolean allowUTF16Surrogates) {
        return FromCodePointNode.getUncached().execute(codepoint, encoding, allowUTF16Surrogates);
    }

    /**
     * Node to create a new {@link TruffleString} from a {@code long} value. See
     * {@link #execute(long, TruffleString.Encoding, boolean)} for details.
     *
     * @since 22.1
     */
    public abstract static class FromLongNode extends AbstractPublicNode {

        FromLongNode() {
        }

        /**
         * Creates a 10's complement string from the given long value, using ASCII digits (0x30 -
         * 0x39). This operation does not support encodings that are incompatible with the ASCII
         * character set.
         *
         * @param lazy if true, the string representation of the number is computed lazily the first
         *            time it is needed. This parameter is expected to be
         *            {@link CompilerAsserts#partialEvaluationConstant(boolean) partial evaluation
         *            constant}.
         *
         * @since 22.1
         */
        public abstract TruffleString execute(long value, Encoding encoding, boolean lazy);

        @Specialization(guards = {"is7BitCompatible(enc)", "lazy"})
        static TruffleString doLazy(long value, Encoding enc, @SuppressWarnings("unused") boolean lazy) {
            CompilerAsserts.partialEvaluationConstant(lazy);
            return TruffleString.createLazyLong(value, enc);
        }

        @Specialization(guards = {"is7BitCompatible(enc)", "!lazy"})
        static TruffleString doEager(long value, Encoding enc, @SuppressWarnings("unused") boolean lazy) {
            CompilerAsserts.partialEvaluationConstant(lazy);
            int length = NumberConversion.stringLengthLong(value);
            int hash = HashCodeNode.maskZero(NumberConversion.computeLongStringHashCode(value));
            return TruffleString.createFromByteArray(NumberConversion.longToString(value, length), 0, length, 0, enc, length, TSCodeRange.get7Bit(), hash, true);
        }

        @Specialization(guards = "!is7BitCompatible(enc)")
        static TruffleString unsupported(@SuppressWarnings("unused") long value, Encoding enc, @SuppressWarnings("unused") boolean lazy) {
            CompilerAsserts.partialEvaluationConstant(lazy);
            throw InternalErrors.unsupportedOperation(nonAsciiCompatibleMessage(enc));
        }

        @TruffleBoundary
        private static String nonAsciiCompatibleMessage(Encoding enc) {
            return "Encoding " + enc + " is not ASCII-compatible";
        }

        /**
         * Create a new {@link FromLongNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static FromLongNode create() {
            return TruffleStringFactory.FromLongNodeGen.create();
        }

        /**
         * Get the uncached version of {@link FromLongNode}.
         *
         * @since 22.1
         */
        public static FromLongNode getUncached() {
            return TruffleStringFactory.FromLongNodeGen.getUncached();
        }
    }

    /**
     * Shorthand for calling the uncached version of {@link FromLongNode}.
     *
     * @since 22.1
     */
    @TruffleBoundary
    public static TruffleString fromLongUncached(long value, Encoding encoding, boolean lazy) {
        return FromLongNode.getUncached().execute(value, encoding, lazy);
    }

    /**
     * Node to create a new {@link TruffleString} from a byte array. See
     * {@link #execute(byte[], int, int, TruffleString.Encoding, boolean)} for details.
     *
     * @since 22.1
     */
    public abstract static class FromByteArrayNode extends AbstractPublicNode {

        FromByteArrayNode() {
        }

        /**
         * Creates a new {@link TruffleString} from a byte array. See
         * {@link #execute(byte[], int, int, TruffleString.Encoding, boolean)} for details.
         *
         * @since 22.1
         */
        public final TruffleString execute(byte[] value, Encoding encoding) {
            return execute(value, encoding, true);
        }

        /**
         * Creates a new {@link TruffleString} from a byte array. See
         * {@link #execute(byte[], int, int, TruffleString.Encoding, boolean)} for details.
         *
         * @since 22.1
         */
        public final TruffleString execute(byte[] value, Encoding encoding, boolean copy) {
            return execute(value, 0, value.length, encoding, copy);
        }

        /**
         * Creates a new {@link TruffleString} from a byte array. The array content is assumed to be
         * encoded in the given encoding already. This operation allows non-copying string creation,
         * i.e. the array parameter can be used directly by passing {@code copy = false}. Caution:
         * {@link TruffleString} assumes the array to be immutable, do not modify the byte array
         * after passing it to the non-copying variant of this operation!
         *
         * @since 22.1
         */
        public abstract TruffleString execute(byte[] value, int byteOffset, int byteLength, Encoding encoding, boolean copy);

        @Specialization
        final TruffleString fromByteArray(byte[] value, int byteOffset, int byteLength, Encoding enc, boolean copy,
                        @Cached TStringInternalNodes.FromBufferWithStringCompactionNode fromBufferWithStringCompactionNode) {
            checkArrayRange(value, byteOffset, byteLength);
            return fromBufferWithStringCompactionNode.execute(this, value, byteOffset, byteLength, enc, copy, true);
        }

        /**
         * Create a new {@link FromByteArrayNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static FromByteArrayNode create() {
            return TruffleStringFactory.FromByteArrayNodeGen.create();
        }

        /**
         * Get the uncached version of {@link FromByteArrayNode}.
         *
         * @since 22.1
         */
        public static FromByteArrayNode getUncached() {
            return TruffleStringFactory.FromByteArrayNodeGen.getUncached();
        }
    }

    /**
     * Shorthand for calling the uncached version of {@link FromByteArrayNode}.
     *
     * @since 22.1
     */
    @TruffleBoundary
    public static TruffleString fromByteArrayUncached(byte[] value, Encoding encoding) {
        return FromByteArrayNode.getUncached().execute(value, encoding);
    }

    /**
     * Shorthand for calling the uncached version of {@link FromByteArrayNode}.
     *
     * @since 22.1
     */
    @TruffleBoundary
    public static TruffleString fromByteArrayUncached(byte[] value, Encoding encoding, boolean copy) {
        return FromByteArrayNode.getUncached().execute(value, encoding, copy);
    }

    /**
     * Shorthand for calling the uncached version of {@link FromByteArrayNode}.
     *
     * @since 22.1
     */
    @TruffleBoundary
    public static TruffleString fromByteArrayUncached(byte[] value, int byteOffset, int byteLength, Encoding encoding, boolean copy) {
        return FromByteArrayNode.getUncached().execute(value, byteOffset, byteLength, encoding, copy);
    }

    /**
     * Node to create a new UTF-16 {@link TruffleString} from a char array.
     *
     * @since 22.1
     */
    public abstract static class FromCharArrayUTF16Node extends AbstractPublicNode {

        FromCharArrayUTF16Node() {
        }

        /**
         * Creates a UTF-16 {@link TruffleString} from a char array.
         *
         * @since 22.1
         */
        public final TruffleString execute(char[] value) {
            return execute(value, 0, value.length);
        }

        /**
         * Creates a UTF-16 {@link TruffleString} from a char-array.
         *
         * @since 22.1
         */
        public abstract TruffleString execute(char[] value, int charOffset, int charLength);

        @Specialization
        final TruffleString doNonEmpty(char[] value, int charOffset, int charLength,
                        @Cached InlinedConditionProfile utf16CompactProfile,
                        @Cached InlinedBranchProfile outOfMemoryProfile) {
            checkArrayRange(value.length, charOffset, charLength);
            if (charLength == 0) {
                return Encoding.UTF_16.getEmpty();
            }
            if (charLength == 1 && value[charOffset] <= 0xff) {
                return TStringConstants.getSingleByte(Encoding.UTF_16, value[charOffset]);
            }
            long offsetV = ((long) charOffset << 1) + charArrayBaseOffset();
            if (value.length > TStringConstants.MAX_ARRAY_SIZE_S1) {
                outOfMemoryProfile.enter(this);
                throw InternalErrors.outOfMemory();
            }
            long attrs = TStringOps.calcStringAttributesUTF16C(this, value, offsetV, charLength);
            final int codePointLength = StringAttributes.getCodePointLength(attrs);
            final int codeRange = StringAttributes.getCodeRange(attrs);
            final int stride = Stride.fromCodeRangeUTF16(codeRange);
            final byte[] array = new byte[charLength << stride];
            if (utf16CompactProfile.profile(this, stride == 0)) {
                TStringOps.arraycopyWithStrideCB(this, value, offsetV, array, byteArrayBaseOffset(), 0, charLength);
            } else {
                TStringOps.arraycopyWithStrideCB(this, value, offsetV, array, byteArrayBaseOffset(), 1, charLength);
            }
            return TruffleString.createFromByteArray(array, charLength, stride, Encoding.UTF_16, codePointLength, codeRange);
        }

        /**
         * Create a new {@link FromCharArrayUTF16Node}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static FromCharArrayUTF16Node create() {
            return TruffleStringFactory.FromCharArrayUTF16NodeGen.create();
        }

        /**
         * Get the uncached version of {@link FromCharArrayUTF16Node}.
         *
         * @since 22.1
         */
        public static FromCharArrayUTF16Node getUncached() {
            return TruffleStringFactory.FromCharArrayUTF16NodeGen.getUncached();
        }
    }

    /**
     * Shorthand for calling the uncached version of {@link FromCharArrayUTF16Node}.
     *
     * @since 22.1
     */
    @TruffleBoundary
    public static TruffleString fromCharArrayUTF16Uncached(char[] value) {
        return FromCharArrayUTF16Node.getUncached().execute(value);
    }

    /**
     * Shorthand for calling the uncached version of {@link FromCharArrayUTF16Node}.
     *
     * @since 22.1
     */
    @TruffleBoundary
    public static TruffleString fromCharArrayUTF16Uncached(char[] value, int charOffset, int charLength) {
        return FromCharArrayUTF16Node.getUncached().execute(value, charOffset, charLength);
    }

    /**
     * Node to create a new {@link TruffleString} from a Java string. See
     * {@link #execute(String, int, int, TruffleString.Encoding, boolean)} for details.
     *
     * @since 22.1
     */
    public abstract static class FromJavaStringNode extends AbstractPublicNode {

        FromJavaStringNode() {
        }

        /**
         * Creates a {@link TruffleString} from a Java string, re-using its internal byte array if
         * possible.
         *
         * @since 22.1
         */
        public final TruffleString execute(String value, Encoding encoding) {
            return execute(value, 0, value.length(), encoding, false);
        }

        /**
         * Creates a {@link TruffleString} from a given region in a Java string, re-using its
         * internal byte array if possible and the region covers the entire string. If {@code copy}
         * is {@code false}, the Java string's internal byte array will be re-used even if the
         * region does not cover the entire string. Note that this will keep the Java string's byte
         * array alive as long as the resulting {@link TruffleString} is alive.
         *
         * @since 22.1
         */
        public abstract TruffleString execute(String value, int charOffset, int length, Encoding encoding, boolean copy);

        @Specialization
        final TruffleString doUTF16(String javaString, int charOffset, int length, Encoding encoding, final boolean copy,
                        @Cached TStringInternalNodes.FromJavaStringUTF16Node fromJavaStringUTF16Node,
                        @Cached InternalSwitchEncodingNode switchEncodingNode,
                        @Cached InlinedConditionProfile utf16Profile) {
            if (javaString.isEmpty()) {
                return encoding.getEmpty();
            }
            TruffleString utf16String = fromJavaStringUTF16Node.execute(this, javaString, charOffset, length, copy);
            if (utf16Profile.profile(this, encoding == Encoding.UTF_16)) {
                return utf16String;
            }
            return switchEncodingNode.execute(this, utf16String, encoding, TranscodingErrorHandler.DEFAULT);
        }

        /**
         * Create a new {@link FromJavaStringNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static FromJavaStringNode create() {
            return TruffleStringFactory.FromJavaStringNodeGen.create();
        }

        /**
         * Get the uncached version of {@link FromJavaStringNode}.
         *
         * @since 22.1
         */
        public static FromJavaStringNode getUncached() {
            return TruffleStringFactory.FromJavaStringNodeGen.getUncached();
        }
    }

    /**
     * Shorthand for calling the uncached version of {@link FromJavaStringNode}.
     * <p>
     * For constant strings, it is recommended to use {@link #fromConstant(String, Encoding)}
     * instead.
     *
     * @since 22.1
     */
    @TruffleBoundary
    public static TruffleString fromJavaStringUncached(String s, Encoding encoding) {
        return FromJavaStringNode.getUncached().execute(s, encoding);
    }

    /**
     * Shorthand for calling the uncached version of {@link FromJavaStringNode}.
     * <p>
     * For constant strings, it is recommended to use {@link #fromConstant(String, Encoding)}
     * instead.
     *
     * @since 22.1
     */
    @TruffleBoundary
    public static TruffleString fromJavaStringUncached(String s, int charOffset, int length, Encoding encoding, boolean copy) {
        return FromJavaStringNode.getUncached().execute(s, charOffset, length, encoding, copy);
    }

    /**
     * Shorthand for calling the uncached version of {@link FromJavaStringNode}. This variant also
     * forces the calculation of the string's precise {@link CodeRange} and hash code.
     *
     * @since 23.0
     */
    @TruffleBoundary
    public static TruffleString fromConstant(String s, Encoding encoding) {
        TruffleString string = FromJavaStringNode.getUncached().execute(s, 0, s.length(), encoding, false);
        string.getCodeRangeUncached(encoding);
        string.hashCodeUncached(encoding);
        return string;
    }

    /**
     * Node to create a new UTF-32 {@link TruffleString} from an int-array.
     *
     * @since 22.1
     */
    public abstract static class FromIntArrayUTF32Node extends AbstractPublicNode {

        FromIntArrayUTF32Node() {
        }

        /**
         * Creates a UTF-32 {@link TruffleString} from an int-array.
         *
         * @since 22.1
         */
        public final TruffleString execute(int[] value) {
            return execute(value, 0, value.length);
        }

        /**
         * Creates a UTF-32 {@link TruffleString} from an int-array.
         *
         * @since 22.1
         */
        public abstract TruffleString execute(int[] value, int intOffset, int intLength);

        @Specialization
        final TruffleString doNonEmpty(int[] value, int intOffset, int length,
                        @Cached InlinedConditionProfile utf32Compact0Profile,
                        @Cached InlinedConditionProfile utf32Compact1Profile,
                        @Cached InlinedBranchProfile outOfMemoryProfile) {
            checkArrayRange(value.length, intOffset, length);
            if (length == 0) {
                return Encoding.UTF_32.getEmpty();
            }
            if (length == 1 && Integer.compareUnsigned(value[intOffset], 0xff) <= 0) {
                return TStringConstants.getSingleByte(Encoding.UTF_32, value[intOffset]);
            }
            long offsetV = ((long) intOffset << 2) + intArrayBaseOffset();
            if (length > TStringConstants.MAX_ARRAY_SIZE_S2) {
                outOfMemoryProfile.enter(this);
                throw InternalErrors.outOfMemory();
            }
            final int codeRange = TStringOps.calcStringAttributesUTF32I(this, value, offsetV, length);
            final int stride = Stride.fromCodeRangeUTF32(codeRange);
            final byte[] array = new byte[length << stride];
            if (utf32Compact0Profile.profile(this, stride == 0)) {
                TStringOps.arraycopyWithStrideIB(this, value, offsetV, array, byteArrayBaseOffset(), 0, length);
            } else if (utf32Compact1Profile.profile(this, stride == 1)) {
                TStringOps.arraycopyWithStrideIB(this, value, offsetV, array, byteArrayBaseOffset(), 1, length);
            } else {
                TStringOps.arraycopyWithStrideIB(this, value, offsetV, array, byteArrayBaseOffset(), 2, length);
            }
            return TruffleString.createFromByteArray(array, length, stride, Encoding.UTF_32, length, codeRange);
        }

        /**
         * Create a new {@link FromIntArrayUTF32Node}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static FromIntArrayUTF32Node create() {
            return TruffleStringFactory.FromIntArrayUTF32NodeGen.create();
        }

        /**
         * Get the uncached version of {@link FromIntArrayUTF32Node}.
         *
         * @since 22.1
         */
        public static FromIntArrayUTF32Node getUncached() {
            return TruffleStringFactory.FromIntArrayUTF32NodeGen.getUncached();
        }
    }

    /**
     * Shorthand for calling the uncached version of {@link FromIntArrayUTF32Node}.
     *
     * @since 22.1
     */
    @TruffleBoundary
    public static TruffleString fromIntArrayUTF32Uncached(int[] value) {
        return FromIntArrayUTF32Node.getUncached().execute(value);
    }

    /**
     * Shorthand for calling the uncached version of {@link FromIntArrayUTF32Node}.
     *
     * @since 22.1
     */
    @TruffleBoundary
    public static TruffleString fromIntArrayUTF32Uncached(int[] value, int intOffset, int intLength) {
        return FromIntArrayUTF32Node.getUncached().execute(value, intOffset, intLength);
    }

    /**
     * Node to create a new {@link TruffleString} from an interop object representing a native
     * pointer. See {@link #execute(Object, int, int, TruffleString.Encoding, boolean)} for details.
     *
     * @since 22.1
     */
    public abstract static class FromNativePointerNode extends AbstractPublicNode {

        FromNativePointerNode() {
        }

        /**
         * Create a new {@link TruffleString} from an interop object representing a native pointer (
         * {@code isPointer(pointerObject)} must return {@code true}). The pointer is immediately
         * unboxed with ({@code asPointer(pointerObject)}) and saved in the {@link TruffleString}
         * instance, i.e. {@link TruffleString} assumes that the pointer address does not change.
         * The pointer's content is assumed to be encoded in the given encoding already. If
         * {@code copy} is {@code false}, the native pointer is used directly as the new string's
         * backing storage. Caution: {@link TruffleString} assumes the pointer's content to be
         * immutable, do not modify the pointer's content after passing it to this operation!
         *
         * <p>
         * <b>WARNING:</b> {@link TruffleString} cannot reason about the lifetime of the native
         * pointer, so it is up to the user to <b>make sure that the native pointer is valid to
         * access and not freed as long the {@code pointerObject} is alive</b> (if {@code copy} is
         * {@code false}). To help with this the TruffleString keeps a reference to the given
         * {@code pointerObject}, so the {@code pointerObject} is kept alive at least as long as the
         * TruffleString is used. In order to be able to use the string past the native pointer's
         * life time, convert it to a managed string via {@link AsManagedNode} <b>before the native
         * pointer is freed</b>.
         * </p>
         * <p>
         * If {@code copy} is {@code true}, the pointer's contents are copied to a Java byte array,
         * and the pointer can be freed safely after the operation completes.
         * </p>
         * This operation requires native access permissions
         * ({@code TruffleLanguage.Env#isNativeAccessAllowed()}).
         *
         * @since 22.1
         */
        public abstract TruffleString execute(Object pointerObject, int byteOffset, int byteLength, Encoding encoding, boolean copy);

        @Specialization
        final TruffleString fromNativePointer(Object pointerObject, int byteOffset, int byteLength, Encoding enc, boolean copy,
                        @Cached(value = "createInteropLibrary()", uncached = "getUncachedInteropLibrary()") Node interopLibrary,
                        @Cached TStringInternalNodes.FromNativePointerNode fromNativePointerNode,
                        @Cached TStringInternalNodes.FromBufferWithStringCompactionNode fromBufferWithStringCompactionNode) {
            NativePointer pointer = NativePointer.create(this, pointerObject, interopLibrary);
            if (copy) {
                return fromBufferWithStringCompactionNode.execute(this, pointer, byteOffset, byteLength, enc, true, true);
            }
            return fromNativePointerNode.execute(this, pointer, byteOffset, byteLength, enc, true);
        }

        /**
         * Create a new {@link FromNativePointerNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static FromNativePointerNode create() {
            return TruffleStringFactory.FromNativePointerNodeGen.create();
        }

        /**
         * Get the uncached version of {@link FromNativePointerNode}.
         *
         * @since 22.1
         */
        public static FromNativePointerNode getUncached() {
            return TruffleStringFactory.FromNativePointerNodeGen.getUncached();
        }
    }

    /**
     * Shorthand for calling the uncached version of {@link FromNativePointerNode}.
     *
     * @since 22.1
     */
    @TruffleBoundary
    public static TruffleString fromNativePointerUncached(Object pointerObject, int byteOffset, int byteLength, Encoding encoding, boolean copy) {
        return FromNativePointerNode.getUncached().execute(pointerObject, byteOffset, byteLength, encoding, copy);
    }

    @TruffleBoundary
    static TruffleString fromNativePointerEmbedder(long rawPointer, int byteOffset, int byteLength, Encoding encoding, boolean copy) {
        return TStringInternalNodesFactory.FromNativePointerEmbedderNodeGen.getUncached().execute(rawPointer, byteOffset, byteLength, encoding, copy);
    }

    /**
     * Node to get the given {@link AbstractTruffleString} as a {@link TruffleString}. See
     * {@link #execute(AbstractTruffleString, TruffleString.Encoding)} for details.
     *
     * @since 22.1
     */
    public abstract static class AsTruffleStringNode extends AbstractPublicNode {

        AsTruffleStringNode() {
        }

        /**
         * If the given string is already a {@link TruffleString}, return it. If it is a
         * {@link MutableTruffleString}, create a new {@link TruffleString}, copying the mutable
         * string's contents.
         *
         * @since 22.1
         */
        public abstract TruffleString execute(AbstractTruffleString value, Encoding expectedEncoding);

        @Specialization
        final TruffleString doDefault(AbstractTruffleString value, Encoding expectedEncoding, @Cached InternalAsTruffleStringNode internalNode) {
            return internalNode.execute(this, value, expectedEncoding);
        }

        /**
         * Create a new {@link AsTruffleStringNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static AsTruffleStringNode create() {
            return TruffleStringFactory.AsTruffleStringNodeGen.create();
        }

        /**
         * Get the uncached version of {@link AsTruffleStringNode}.
         *
         * @since 22.1
         */
        public static AsTruffleStringNode getUncached() {
            return TruffleStringFactory.AsTruffleStringNodeGen.getUncached();
        }
    }

    abstract static class InternalAsTruffleStringNode extends AbstractInternalNode {

        /**
         * If the given string is already a {@link TruffleString}, return it. If it is a
         * {@link MutableTruffleString}, create a new {@link TruffleString}, copying the mutable
         * string's contents.
         *
         * @since 22.1
         */
        abstract TruffleString execute(Node node, AbstractTruffleString value, Encoding expectedEncoding);

        @Specialization
        static TruffleString immutable(TruffleString a, Encoding expectedEncoding) {
            a.checkEncoding(expectedEncoding);
            return a;
        }

        @Specialization
        static TruffleString fromMutableString(Node node, MutableTruffleString a, Encoding expectedEncoding,
                        @Cached TStringInternalNodes.FromBufferWithStringCompactionKnownAttributesNode fromBufferWithStringCompactionNode) {
            return fromBufferWithStringCompactionNode.execute(node, a, expectedEncoding);
        }
    }

    /**
     * Node to get the given {@link AbstractTruffleString} as a managed {@link TruffleString},
     * meaning that the resulting string's backing memory is not a native pointer. See
     * {@link #execute(AbstractTruffleString, TruffleString.Encoding)} for details.
     *
     * @since 22.1
     */
    public abstract static class AsManagedNode extends AbstractPublicNode {

        AsManagedNode() {
        }

        /**
         * Returns a given string if it is already managed (i.e. not backed by a native pointer),
         * otherwise, copies the string's native pointer content into a Java byte array and returns
         * a new string backed by the byte array.
         *
         * @since 22.1
         */
        public final TruffleString execute(AbstractTruffleString a, Encoding expectedEncoding) {
            return execute(a, expectedEncoding, false);
        }

        /**
         * If the given string is already a managed (i.e. not backed by a native pointer) string,
         * return it. Otherwise, copy the string's native pointer content into a Java byte array and
         * return a new string backed by the byte array.
         *
         * @param cacheResult if set to {@code true}, managed strings created from native
         *            {@link TruffleString} instances are cached in the native string's internal
         *            transcoding cache. Note that this will keep the native string alive and
         *            reachable via {@link AsNativeNode}. Subsequent calls of {@link AsManagedNode}
         *            with {@code cacheResult=true} on the same native string are guaranteed to
         *            return the same managed string. This parameter is expected to be
         *            {@link CompilerAsserts#partialEvaluationConstant(boolean) partial evaluation
         *            constant}.
         * @since 23.0
         */
        public abstract TruffleString execute(AbstractTruffleString a, Encoding expectedEncoding, boolean cacheResult);

        @Specialization(guards = "!a.isNative()")
        static TruffleString managedImmutable(TruffleString a, Encoding expectedEncoding, boolean cacheResult) {
            CompilerAsserts.partialEvaluationConstant(cacheResult);
            a.checkEncoding(expectedEncoding);
            assert !(a.data() instanceof NativePointer);
            return a;
        }

        @Specialization(guards = "a.isNative()")
        TruffleString nativeImmutable(TruffleString a, Encoding encoding, boolean cacheResult,
                        @Cached InlinedConditionProfile cacheHit,
                        @Shared("attributesNode") @Cached TStringInternalNodes.FromBufferWithStringCompactionKnownAttributesNode attributesNode) {
            CompilerAsserts.partialEvaluationConstant(cacheResult);
            a.checkEncoding(encoding);
            TruffleString cur = a.next;
            assert !a.isJavaString();
            if (cacheResult && cur != null) {
                while (cur != a && (cur.isNative() || cur.isJavaString() || !cur.isCompatibleToIntl(encoding))) {
                    cur = cur.next;
                }
                if (cacheHit.profile(this, cur != a)) {
                    assert cur.isCompatibleToIntl(encoding) && cur.isManaged() && !cur.isJavaString();
                    return cur;
                }
            }
            TruffleString managed = attributesNode.execute(this, a, !cacheResult, encoding);
            if (cacheResult) {
                a.cacheInsert(managed);
            }
            return managed;
        }

        @Specialization
        TruffleString mutable(MutableTruffleString a, Encoding expectedEncoding, boolean cacheResult,
                        @Shared("attributesNode") @Cached TStringInternalNodes.FromBufferWithStringCompactionKnownAttributesNode attributesNode) {
            CompilerAsserts.partialEvaluationConstant(cacheResult);
            a.checkEncoding(expectedEncoding);
            return attributesNode.execute(this, a, expectedEncoding);
        }

        /**
         * Create a new {@link AsManagedNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static AsManagedNode create() {
            return TruffleStringFactory.AsManagedNodeGen.create();
        }

        /**
         * Get the uncached version of {@link AsManagedNode}.
         *
         * @since 22.1
         */
        public static AsManagedNode getUncached() {
            return TruffleStringFactory.AsManagedNodeGen.getUncached();
        }
    }

    /**
     * Node to force materialization of any lazy internal data. Use this node to avoid
     * materialization code inside loops, e.g. when iterating over a string's code points or bytes.
     *
     * @since 22.1
     */
    public abstract static class MaterializeNode extends AbstractPublicNode {

        MaterializeNode() {
        }

        /**
         * Forces materialization of any lazy internal data. Use this node to avoid materialization
         * code inside loops, e.g. when iterating over a string's code points or bytes.
         *
         * @since 22.1
         */
        public abstract void execute(AbstractTruffleString a, Encoding expectedEncoding);

        @Specialization
        final void doMaterialize(AbstractTruffleString a, Encoding expectedEncoding,
                        @Cached ToIndexableNode toIndexableNode) {
            a.checkEncoding(expectedEncoding);
            toIndexableNode.execute(this, a, a.data());
            assert a.isMaterialized();
        }

        /**
         * Create a new {@link MaterializeNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static MaterializeNode create() {
            return TruffleStringFactory.MaterializeNodeGen.create();
        }

        /**
         * Get the uncached version of {@link MaterializeNode}.
         *
         * @since 22.1
         */
        public static MaterializeNode getUncached() {
            return TruffleStringFactory.MaterializeNodeGen.getUncached();
        }
    }

    /**
     * Node to get a string's precise {@link CodeRange}.
     *
     * @see CodeRange
     * @see GetCodeRangeImpreciseNode
     *
     * @since 22.1
     */
    public abstract static class GetCodeRangeNode extends AbstractPublicNode {

        GetCodeRangeNode() {
        }

        /**
         * Get the string's precise {@link CodeRange}. This operation will cause a full string scan
         * if the precise code range is currently unknown.
         *
         * @since 22.1
         */
        public abstract CodeRange execute(AbstractTruffleString a, Encoding expectedEncoding);

        @Specialization
        final CodeRange getCodeRange(AbstractTruffleString a, Encoding expectedEncoding,
                        @Cached TStringInternalNodes.GetPreciseCodeRangeWithMaterializationNode getPreciseCodeRangeNode) {
            a.checkEncoding(expectedEncoding);
            return CodeRange.get(getPreciseCodeRangeNode.execute(this, a, expectedEncoding));
        }

        /**
         * Create a new {@link GetCodeRangeNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static GetCodeRangeNode create() {
            return TruffleStringFactory.GetCodeRangeNodeGen.create();
        }

        /**
         * Get the uncached version of {@link GetCodeRangeNode}.
         *
         * @since 22.1
         */
        public static GetCodeRangeNode getUncached() {
            return TruffleStringFactory.GetCodeRangeNodeGen.getUncached();
        }
    }

    /**
     * Node to get a string's possibly imprecise {@link CodeRange}.
     *
     * @see CodeRange
     * @see GetCodeRangeNode
     *
     * @since 23.0
     */
    public abstract static class GetCodeRangeImpreciseNode extends AbstractPublicNode {

        GetCodeRangeImpreciseNode() {
        }

        /**
         * Get the string's possibly imprecise {@link CodeRange}. This node will never attempt to
         * calculate a more precise code range, instead it will only return what is currently known.
         *
         * @since 23.0
         */
        public abstract CodeRange execute(AbstractTruffleString a, Encoding expectedEncoding);

        @Specialization
        static CodeRange getCodeRange(AbstractTruffleString a, Encoding expectedEncoding) {
            a.checkEncoding(expectedEncoding);
            return CodeRange.get(a.codeRange());
        }

        /**
         * Create a new {@link GetCodeRangeImpreciseNode}.
         *
         * @since 23.0
         */
        @NeverDefault
        public static GetCodeRangeImpreciseNode create() {
            return TruffleStringFactory.GetCodeRangeImpreciseNodeGen.create();
        }

        /**
         * Get the uncached version of {@link GetCodeRangeImpreciseNode}.
         *
         * @since 23.0
         */
        public static GetCodeRangeImpreciseNode getUncached() {
            return TruffleStringFactory.GetCodeRangeImpreciseNodeGen.getUncached();
        }
    }

    /**
     * Node to get a string's "byte-based" {@link CodeRange}. See
     * {@link #execute(AbstractTruffleString, TruffleString.Encoding)} for details.
     *
     * @since 22.1
     */
    public abstract static class GetByteCodeRangeNode extends AbstractPublicNode {

        GetByteCodeRangeNode() {
        }

        /**
         * Get the string's "byte-based" {@link CodeRange}. This differs from
         * {@link GetCodeRangeNode} in the following way:
         * <ul>
         * <li>A string is only considered to be in the {@link CodeRange#ASCII} code range if its
         * encoding is byte-based, so {@link Encoding#UTF_16} and {@link Encoding#UTF_32} cannot be
         * {@link CodeRange#ASCII}.</li>
         * <li>{@link CodeRange#LATIN_1} and {@link CodeRange#BMP} are mapped to
         * {@link CodeRange#VALID}</li>.
         * </ul>
         * The return value is always one of {@link CodeRange#ASCII}, {@link CodeRange#VALID} or
         * {@link CodeRange#BROKEN}.
         *
         * @since 22.1
         */
        public abstract CodeRange execute(AbstractTruffleString a, Encoding expectedEncoding);

        @Specialization
        final CodeRange getCodeRange(AbstractTruffleString a, Encoding expectedEncoding,
                        @Cached TStringInternalNodes.GetPreciseCodeRangeWithMaterializationNode getPreciseCodeRangeNode) {
            a.checkEncoding(expectedEncoding);
            return CodeRange.getByteCodeRange(getPreciseCodeRangeNode.execute(this, a, expectedEncoding), expectedEncoding);
        }

        /**
         * Create a new {@link GetByteCodeRangeNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static GetByteCodeRangeNode create() {
            return TruffleStringFactory.GetByteCodeRangeNodeGen.create();
        }

        /**
         * Get the uncached version of {@link GetByteCodeRangeNode}.
         *
         * @since 22.1
         */
        public static GetByteCodeRangeNode getUncached() {
            return TruffleStringFactory.GetByteCodeRangeNodeGen.getUncached();
        }
    }

    /**
     * Node to check if a string's code range is equal to the given {@link CodeRange}. See
     * {@link #execute(AbstractTruffleString, TruffleString.CodeRange)} for details.
     *
     * @since 22.1
     */
    public abstract static class CodeRangeEqualsNode extends AbstractPublicNode {

        CodeRangeEqualsNode() {
        }

        /**
         * Returns {@code true} if the string's code range is equal to the given {@link CodeRange}.
         * Use this operation when caching code range instances, e.g.:
         *
         * <pre>
         * {@code
         * &#64;Specialization(guards = "codeRangeEqualsNode.execute(a, cachedCodeRange)")
         * static void someOperation(TString a,
         *                 &#64;Cached TruffleString.GetCodeRangeNode getCodeRangeNode,
         *                 &#64;Cached TruffleString.CodeRangeEqualsNode codeRangeEqualsNode,
         *                 &#64;Cached("getCodeRangeNode.execute(this, a)") CodeRange cachedCodeRange) {
         *     // ...
         * }
         * }
         * </pre>
         *
         * @since 22.1
         */
        public abstract boolean execute(AbstractTruffleString a, CodeRange codeRange);

        @Specialization
        final boolean codeRangeEquals(AbstractTruffleString a, CodeRange codeRange,
                        @Cached TStringInternalNodes.GetPreciseCodeRangeWithMaterializationNode getPreciseCodeRangeNode) {
            return CodeRange.equals(getPreciseCodeRangeNode.execute(this, a, Encoding.get(a.encoding())), codeRange);
        }

        /**
         * Create a new {@link CodeRangeEqualsNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static CodeRangeEqualsNode create() {
            return TruffleStringFactory.CodeRangeEqualsNodeGen.create();
        }

        /**
         * Get the uncached version of {@link CodeRangeEqualsNode}.
         *
         * @since 22.1
         */
        public static CodeRangeEqualsNode getUncached() {
            return TruffleStringFactory.CodeRangeEqualsNodeGen.getUncached();
        }
    }

    /**
     * Node to check if a string is encoded correctly.
     *
     * @since 22.1
     */
    public abstract static class IsValidNode extends AbstractPublicNode {

        IsValidNode() {
        }

        /**
         * Returns {@code true} if the string encoded correctly.
         *
         * @since 22.1
         */
        public abstract boolean execute(AbstractTruffleString a, Encoding expectedEncoding);

        @Specialization
        final boolean isValid(AbstractTruffleString a, Encoding expectedEncoding,
                        @Cached TStringInternalNodes.GetValidOrBrokenCodeRangeNode getCodeRangeNode) {
            a.checkEncoding(expectedEncoding);
            return !isBroken(getCodeRangeNode.execute(this, a, expectedEncoding));
        }

        /**
         * Create a new {@link IsValidNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static IsValidNode create() {
            return TruffleStringFactory.IsValidNodeGen.create();
        }

        /**
         * Get the uncached version of {@link IsValidNode}.
         *
         * @since 22.1
         */
        public static IsValidNode getUncached() {
            return TruffleStringFactory.IsValidNodeGen.getUncached();
        }
    }

    /**
     * Represents a string's compaction level, i.e. the internal number of bytes per array element.
     * This is relevant only for {@link Encoding#UTF_16} and {@link Encoding#UTF_32}, since
     * TruffleString doesn't support string compaction on any other encoding.
     *
     * @since 23.0
     */
    public enum CompactionLevel {
        /**
         * One byte per array element.
         *
         * @since 23.0
         */
        S1(1, 0),
        /**
         * Two bytes per array element.
         *
         * @since 23.0
         */
        S2(2, 1),
        /**
         * Four bytes per array element.
         *
         * @since 23.0
         */
        S4(4, 2);

        private final int bytes;
        private final int log2;

        CompactionLevel(int bytes, int log2) {
            this.bytes = bytes;
            this.log2 = log2;
        }

        int getStride() {
            return log2;
        }

        /**
         * Get the number of bytes per internal array element.
         *
         * @since 23.0
         */
        public final int getBytes() {
            return bytes;
        }

        /**
         * Get the number of bytes per internal array element in log2 format.
         *
         * @since 23.0
         */
        public final int getLog2() {
            return log2;
        }

        static CompactionLevel fromStride(int stride) {
            assert Stride.isStride(stride);
            if (stride == 0) {
                return S1;
            }
            if (stride == 1) {
                return S2;
            }
            assert stride == 2;
            return S4;
        }
    }

    /**
     * Node to get a string's {@link CompactionLevel}.
     *
     * @since 23.0
     */
    public abstract static class GetStringCompactionLevelNode extends AbstractPublicNode {

        GetStringCompactionLevelNode() {
        }

        /**
         * Get the string's {@link CompactionLevel}. Since string compaction is only supported on
         * {@link Encoding#UTF_16} and {@link Encoding#UTF_32}, this node will return
         * {@link CompactionLevel#S1} on all other encodings.
         *
         * @since 23.0
         */
        public abstract CompactionLevel execute(AbstractTruffleString a, Encoding expectedEncoding);

        @Specialization
        static CompactionLevel getStringCompactionLevel(AbstractTruffleString a, Encoding expectedEncoding) {
            a.checkEncoding(expectedEncoding);
            int stride = a.stride();
            if (isPartialEvaluationConstant(expectedEncoding)) {
                if (isUTF16(expectedEncoding)) {
                    return stride == 0 ? CompactionLevel.S1 : CompactionLevel.S2;
                } else if (isUTF32(expectedEncoding)) {
                    return CompactionLevel.fromStride(stride);
                } else {
                    return CompactionLevel.S1;
                }
            }
            return CompactionLevel.fromStride(stride);
        }

        /**
         * Create a new {@link GetStringCompactionLevelNode}.
         *
         * @since 23.0
         */
        @NeverDefault
        public static GetStringCompactionLevelNode create() {
            return TruffleStringFactory.GetStringCompactionLevelNodeGen.create();
        }

        /**
         * Get the uncached version of {@link GetStringCompactionLevelNode}.
         *
         * @since 23.0
         */
        public static GetStringCompactionLevelNode getUncached() {
            return TruffleStringFactory.GetStringCompactionLevelNodeGen.getUncached();
        }
    }

    /**
     * Node to get the number of codepoints in a string.
     *
     * @since 22.1
     */
    public abstract static class CodePointLengthNode extends AbstractPublicNode {

        CodePointLengthNode() {
        }

        /**
         * Return the number of codepoints in the string.
         * <p>
         * If the string is not encoded correctly (if its coderange is {@link CodeRange#BROKEN}),
         * every broken minimum-length sequence in the encoding (4 bytes for UTF-32, 2 bytes for
         * UTF-16, 1 byte for other encodings) adds 1 to the length.
         *
         * @since 22.1
         */
        public abstract int execute(AbstractTruffleString a, Encoding expectedEncoding);

        @Specialization
        final int get(AbstractTruffleString a, Encoding expectedEncoding,
                        @Cached TStringInternalNodes.GetCodePointLengthWithMaterializationNode getCodePointLengthNode) {
            a.checkEncoding(expectedEncoding);
            return getCodePointLengthNode.execute(this, a, expectedEncoding);
        }

        /**
         * Create a new {@link CodePointLengthNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static CodePointLengthNode create() {
            return TruffleStringFactory.CodePointLengthNodeGen.create();
        }

        /**
         * Get the uncached version of {@link CodePointLengthNode}.
         *
         * @since 22.1
         */
        public static CodePointLengthNode getUncached() {
            return TruffleStringFactory.CodePointLengthNodeGen.getUncached();
        }
    }

    /**
     * Node to get a string's hash code. See
     * {@link #execute(AbstractTruffleString, TruffleString.Encoding)} for details.
     *
     * @see TruffleString#hashCode()
     * @since 22.1
     */
    public abstract static class HashCodeNode extends AbstractPublicNode {

        HashCodeNode() {
        }

        /**
         * Returns the string's hash code. The hash is dependent on the string's encoding, make sure
         * to convert strings to a common encoding before comparing their hash codes!
         *
         * @since 22.1
         */
        public abstract int execute(AbstractTruffleString a, Encoding expectedEncoding);

        @Specialization
        final int calculateHash(AbstractTruffleString a, Encoding expectedEncoding,
                        @Cached InlinedConditionProfile cacheMiss,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringOpsNodes.CalculateHashCodeNode calculateHashCodeNode) {
            a.checkEncoding(expectedEncoding);
            int h = a.hashCode;
            if (cacheMiss.profile(this, h == 0)) {
                Object dataA = a.data();
                try {
                    final byte[] arrayA;
                    final long addOffsetA;
                    if (managedProfileA.profile(this, dataA instanceof byte[])) {
                        arrayA = (byte[]) dataA;
                        addOffsetA = byteArrayBaseOffset();
                    } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                        arrayA = null;
                        addOffsetA = NativePointer.unwrap(dataA);
                    } else {
                        arrayA = a.materializeLazy(this, dataA);
                        addOffsetA = byteArrayBaseOffset();
                    }
                    final long offsetA = a.offset() + addOffsetA;
                    h = a.setHashCode(maskZero(calculateHashCodeNode.execute(this, a, arrayA, offsetA)));
                } finally {
                    Reference.reachabilityFence(dataA);
                }
            }
            return h;
        }

        static int maskZero(int rawHashCode) {
            int h = rawHashCode;
            if (h == 0) {
                h--;
                assert h == MASKED_ZERO_HASH_CODE;
            }
            return h;
        }

        /**
         * Create a new {@link HashCodeNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static HashCodeNode create() {
            return TruffleStringFactory.HashCodeNodeGen.create();
        }

        /**
         * Get the uncached version of {@link HashCodeNode}.
         *
         * @since 22.1
         */
        public static HashCodeNode getUncached() {
            return TruffleStringFactory.HashCodeNodeGen.getUncached();
        }

        /**
         * Calculates the hash code for {@link AbstractTruffleString#hashCode()}. This method is
         * only called if the hashCode field is zero, so we don't need to check that again here.
         */
        static int calculateHashCodeUncached(AbstractTruffleString a) {
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (dataA instanceof byte[]) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (dataA instanceof NativePointer) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(getUncached(), dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;
                return a.setHashCode(maskZero(TStringOps.hashCodeWithStride(getUncached(), a, arrayA, offsetA, a.stride())));
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }
    }

    /**
     * Node to read a single byte from a string. See
     * {@link #execute(AbstractTruffleString, int, TruffleString.Encoding)} for details.
     *
     * @since 22.1
     */
    public abstract static class ReadByteNode extends AbstractPublicNode {

        ReadByteNode() {
        }

        /**
         * Read a single byte from a string. If used inside a loop or repetitively,
         * {@link MaterializeNode} should be used before.
         *
         * @since 22.1
         */
        public abstract int execute(AbstractTruffleString a, int byteIndex, Encoding expectedEncoding);

        @Specialization
        final int doRead(AbstractTruffleString a, int i, Encoding expectedEncoding,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.ReadByteNode readByteNode) {
            a.checkEncoding(expectedEncoding);
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;
                return readByteNode.execute(this, a, arrayA, offsetA, i, expectedEncoding);
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link ReadByteNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static ReadByteNode create() {
            return TruffleStringFactory.ReadByteNodeGen.create();
        }

        /**
         * Get the uncached version of {@link ReadByteNode}.
         *
         * @since 22.1
         */
        public static ReadByteNode getUncached() {
            return TruffleStringFactory.ReadByteNodeGen.getUncached();
        }
    }

    /**
     * Node to read a single char from a UTF-16 string.
     *
     * @since 22.1
     */
    public abstract static class ReadCharUTF16Node extends AbstractPublicNode {

        ReadCharUTF16Node() {
        }

        /**
         * Read a single char from a UTF-16 string.
         *
         * @since 22.1
         */
        public abstract char execute(AbstractTruffleString a, int charIndex);

        @Specialization
        final char doRead(AbstractTruffleString a, int i,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached InlinedConditionProfile utf16S0Profile) {
            a.checkEncoding(Encoding.UTF_16);
            a.boundsCheckRaw(i);
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;
                if (utf16S0Profile.profile(this, isStride0(a))) {
                    return (char) TStringOps.readS0(a, arrayA, offsetA, i);
                } else {
                    assert isStride1(a);
                    return TStringOps.readS1(a, arrayA, offsetA, i);
                }
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link ReadCharUTF16Node}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static ReadCharUTF16Node create() {
            return TruffleStringFactory.ReadCharUTF16NodeGen.create();
        }

        /**
         * Get the uncached version of {@link ReadCharUTF16Node}.
         *
         * @since 22.1
         */
        public static ReadCharUTF16Node getUncached() {
            return TruffleStringFactory.ReadCharUTF16NodeGen.getUncached();
        }
    }

    /**
     * Node to get the number of bytes occupied by the codepoint starting at a given byte index. See
     * {@link #execute(AbstractTruffleString, int, TruffleString.Encoding)} for details.
     *
     * @since 22.1
     */
    public abstract static class ByteLengthOfCodePointNode extends AbstractPublicNode {

        ByteLengthOfCodePointNode() {
        }

        /**
         * Get the number of bytes occupied by the codepoint starting at {@code byteIndex}, with
         * {@link ErrorHandling#BEST_EFFORT best-effort} error handling.
         *
         * @since 22.1
         */
        public final int execute(AbstractTruffleString a, int byteIndex, Encoding expectedEncoding) {
            return execute(a, byteIndex, expectedEncoding, ErrorHandling.BEST_EFFORT);
        }

        /**
         * Get the number of bytes occupied by the codepoint starting at {@code byteIndex}.
         *
         * @param errorHandling if set to {@link ErrorHandling#BEST_EFFORT}, this node will return
         *            the encoding's minimum number of bytes per codepoint if an error occurs while
         *            reading the codepoint. If set to {@link ErrorHandling#RETURN_NEGATIVE}, a
         *            negative value will be returned instead, where two error cases are
         *            distinguished: if the codepoint is invalid, the return value is -1. If the
         *            codepoint is an unfinished, possibly valid byte sequence at the end of the
         *            string, the return value is {@code -1 - (number of missing bytes)}. This
         *            parameter is expected to be
         *            {@link CompilerAsserts#partialEvaluationConstant(Object) partial evaluation
         *            constant}.
         *
         * @since 22.3
         */
        public abstract int execute(AbstractTruffleString a, int byteIndex, Encoding expectedEncoding, ErrorHandling errorHandling);

        @Specialization
        final int translate(AbstractTruffleString a, int byteIndex, Encoding encoding, ErrorHandling errorHandling,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeNode,
                        @Cached TStringInternalNodes.ByteLengthOfCodePointNode byteLengthOfCodePointNode) {
            CompilerAsserts.partialEvaluationConstant(errorHandling);
            a.checkEncoding(encoding);
            int rawIndex = rawIndex(byteIndex, encoding);
            a.boundsCheckRaw(rawIndex);
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;
                int codeRangeA = getCodeRangeNode.execute(this, a, arrayA, offsetA, encoding);
                return byteLengthOfCodePointNode.execute(this, a, arrayA, offsetA, codeRangeA, encoding, rawIndex, errorHandling);
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link ByteLengthOfCodePointNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static ByteLengthOfCodePointNode create() {
            return TruffleStringFactory.ByteLengthOfCodePointNodeGen.create();
        }

        /**
         * Get the uncached version of {@link ByteLengthOfCodePointNode}.
         *
         * @since 22.1
         */
        public static ByteLengthOfCodePointNode getUncached() {
            return TruffleStringFactory.ByteLengthOfCodePointNodeGen.getUncached();
        }
    }

    /**
     * Node to convert a given byte index to a codepoint index. See
     * {@link #execute(AbstractTruffleString, int, int, TruffleString.Encoding)} for details.
     *
     * @since 22.2
     */
    public abstract static class ByteIndexToCodePointIndexNode extends AbstractPublicNode {

        ByteIndexToCodePointIndexNode() {
        }

        /**
         * Convert the given byte index to a codepoint index, relative to starting point
         * {@code byteOffset}.
         *
         * @since 22.2
         */
        public abstract int execute(AbstractTruffleString a, int byteOffset, int byteIndex, Encoding expectedEncoding);

        @Specialization
        final int translate(AbstractTruffleString a, int byteOffset, int byteIndex, Encoding encoding,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeNode,
                        @Cached TStringInternalNodes.RawIndexToCodePointIndexNode rawIndexToCodePointIndexNode) {
            a.checkEncoding(encoding);
            int rawOffset = rawIndex(byteOffset, encoding);
            int rawIndex = rawIndex(byteIndex, encoding);
            a.boundsCheckRegionRaw(rawOffset, rawIndex);
            if (byteIndex == 0) {
                return 0;
            }
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;
                int codeRangeA = getCodeRangeNode.execute(this, a, arrayA, offsetA, encoding);
                return rawIndexToCodePointIndexNode.execute(this, a, arrayA, offsetA, codeRangeA, encoding, byteOffset, rawIndex);
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link ByteIndexToCodePointIndexNode}.
         *
         * @since 22.2
         */
        @NeverDefault
        public static ByteIndexToCodePointIndexNode create() {
            return TruffleStringFactory.ByteIndexToCodePointIndexNodeGen.create();
        }

        /**
         * Get the uncached version of {@link ByteIndexToCodePointIndexNode}.
         *
         * @since 22.2
         */
        public static ByteIndexToCodePointIndexNode getUncached() {
            return TruffleStringFactory.ByteIndexToCodePointIndexNodeGen.getUncached();
        }
    }

    /**
     * Node to convert a given codepoint index to a byte index. See
     * {@link #execute(AbstractTruffleString, int, int, TruffleString.Encoding)} for details.
     *
     * @since 22.1
     */
    public abstract static class CodePointIndexToByteIndexNode extends AbstractPublicNode {

        CodePointIndexToByteIndexNode() {
        }

        /**
         * Convert the given codepoint index to a byte index, relative to starting point
         * {@code byteOffset}.
         *
         * @since 22.1
         */
        public abstract int execute(AbstractTruffleString a, int byteOffset, int codepointIndex, Encoding expectedEncoding);

        @Specialization
        final int translate(AbstractTruffleString a, int byteOffset, int codepointIndex, Encoding encoding,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.GetCodePointLengthNode getCodePointLengthNode,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeNode,
                        @Cached TStringInternalNodes.CodePointIndexToRawNode codePointIndexToRawNode) {
            a.checkEncoding(encoding);
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;
                a.boundsCheckRegion(this, arrayA, offsetA, 0, codepointIndex, encoding, getCodePointLengthNode);
                int rawOffset = rawIndex(byteOffset, encoding);
                a.boundsCheckRawLength(rawOffset);
                if (codepointIndex == 0) {
                    return 0;
                }
                int codeRangeA = getCodeRangeNode.execute(this, a, arrayA, offsetA, encoding);
                return codePointIndexToRawNode.execute(this, a, arrayA, offsetA, codeRangeA, encoding, rawOffset, codepointIndex, true) << encoding.naturalStride;
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link CodePointIndexToByteIndexNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static CodePointIndexToByteIndexNode create() {
            return TruffleStringFactory.CodePointIndexToByteIndexNodeGen.create();
        }

        /**
         * Get the uncached version of {@link CodePointIndexToByteIndexNode}.
         *
         * @since 22.1
         */
        public static CodePointIndexToByteIndexNode getUncached() {
            return TruffleStringFactory.CodePointIndexToByteIndexNodeGen.getUncached();
        }
    }

    /**
     * Node to read a codepoint at a given codepoint index. See
     * {@link #execute(AbstractTruffleString, int, TruffleString.Encoding)} for details.
     *
     * @since 22.1
     */
    public abstract static class CodePointAtIndexNode extends AbstractPublicNode {

        CodePointAtIndexNode() {
        }

        /**
         * Decode and return the codepoint at codepoint index {@code i}, with
         * {@link ErrorHandling#BEST_EFFORT best-effort} error handling.
         *
         * @since 22.1
         */
        public final int execute(AbstractTruffleString a, int i, Encoding expectedEncoding) {
            return execute(a, i, expectedEncoding, ErrorHandling.BEST_EFFORT);
        }

        /**
         * Decode and return the codepoint at codepoint index {@code i}.
         *
         * @param errorHandling if set to {@link ErrorHandling#BEST_EFFORT}, the return value on
         *            invalid codepoints depends on {@code expectedEncoding}:
         *            <ul>
         *            <li>{@link Encoding#UTF_8}: Unicode Replacement character {@code 0xFFFD}</li>
         *            <li>{@link Encoding#UTF_16}: the (16-bit) {@code char} value read at index
         *            {@code i}</li>
         *            <li>{@link Encoding#UTF_32}: the (32-bit) {@code int} value read at index
         *            {@code i}</li>
         *            <li>{@link Encoding#US_ASCII}, {@link Encoding#ISO_8859_1},
         *            {@link Encoding#BYTES}: the (8-bit) unsigned {@code byte} value read at index
         *            {@code i}</li>
         *            <li>All other Encodings: Unicode Replacement character {@code 0xFFFD}</li>
         *            </ul>
         *            If set to {@link ErrorHandling#RETURN_NEGATIVE}, {@code -1} will be returned
         *            instead. This parameter is expected to be
         *            {@link CompilerAsserts#partialEvaluationConstant(Object) partial evaluation
         *            constant}.
         *
         * @since 22.3
         */
        public abstract int execute(AbstractTruffleString a, int i, Encoding expectedEncoding, ErrorHandling errorHandling);

        @Specialization
        final int readCodePoint(AbstractTruffleString a, int i, Encoding encoding, ErrorHandling errorHandling,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.GetCodePointLengthNode getCodePointLengthNode,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeNode,
                        @Cached TStringInternalNodes.CodePointAtNode readCodePointNode) {
            CompilerAsserts.partialEvaluationConstant(errorHandling);
            a.checkEncoding(encoding);
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;
                a.boundsCheck(this, arrayA, offsetA, i, encoding, getCodePointLengthNode);
                int codeRangeA = getCodeRangeNode.execute(this, a, arrayA, offsetA, encoding);
                return readCodePointNode.execute(this, a, arrayA, offsetA, codeRangeA, encoding, i, errorHandling);
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link CodePointAtIndexNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static CodePointAtIndexNode create() {
            return TruffleStringFactory.CodePointAtIndexNodeGen.create();
        }

        /**
         * Get the uncached version of {@link CodePointAtIndexNode}.
         *
         * @since 22.1
         */
        public static CodePointAtIndexNode getUncached() {
            return TruffleStringFactory.CodePointAtIndexNodeGen.getUncached();
        }
    }

    /**
     * Node to read a codepoint at a given byte index. See
     * {@link #execute(AbstractTruffleString, int, TruffleString.Encoding)} for details.
     *
     * @since 22.1
     */
    public abstract static class CodePointAtByteIndexNode extends AbstractPublicNode {

        CodePointAtByteIndexNode() {
        }

        /**
         * Decode and return the codepoint at byte index {@code i}, with
         * {@link ErrorHandling#BEST_EFFORT best-effort} error handling.
         *
         * @since 22.1
         */
        public final int execute(AbstractTruffleString a, int i, Encoding expectedEncoding) {
            return execute(a, i, expectedEncoding, ErrorHandling.BEST_EFFORT);
        }

        /**
         * Decode and return the codepoint at byte index {@code i}.
         *
         * @param errorHandling analogous to {@link CodePointAtIndexNode}.
         *
         * @since 22.3
         */
        public abstract int execute(AbstractTruffleString a, int i, Encoding expectedEncoding, ErrorHandling errorHandling);

        @Specialization
        final int readCodePoint(AbstractTruffleString a, int byteIndex, Encoding encoding, ErrorHandling errorHandling,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeNode,
                        @Cached TStringInternalNodes.CodePointAtRawNode readCodePointNode) {
            CompilerAsserts.partialEvaluationConstant(errorHandling);
            final int i = rawIndex(byteIndex, encoding);
            a.checkEncoding(encoding);
            a.boundsCheckRaw(i);
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;
                int codeRangeA = getCodeRangeNode.execute(this, a, arrayA, offsetA, encoding);
                return readCodePointNode.execute(this, a, arrayA, offsetA, codeRangeA, encoding, i, errorHandling);
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link CodePointAtByteIndexNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static CodePointAtByteIndexNode create() {
            return TruffleStringFactory.CodePointAtByteIndexNodeGen.create();
        }

        /**
         * Get the uncached version of {@link CodePointAtByteIndexNode}.
         *
         * @since 22.1
         */
        public static CodePointAtByteIndexNode getUncached() {
            return TruffleStringFactory.CodePointAtByteIndexNodeGen.getUncached();
        }
    }

    /**
     * Node to find the index of the first occurrence of any byte from a given array. See
     * {@link #execute(AbstractTruffleString, int, int, byte[], TruffleString.Encoding)} for
     * details.
     *
     * @since 22.1
     */
    public abstract static class ByteIndexOfAnyByteNode extends AbstractPublicNode {

        ByteIndexOfAnyByteNode() {
        }

        /**
         * Return the byte index of the first occurrence of any byte contained in {@code values},
         * bounded by {@code fromByteIndex} (inclusive) and {@code maxByteIndex} (exclusive).
         * <p>
         * If none of the values is found, return a negative value.
         *
         * @since 22.1
         */
        public abstract int execute(AbstractTruffleString a, int fromByteIndex, int maxByteIndex, byte[] values, Encoding expectedEncoding);

        @Specialization
        int indexOfRaw(AbstractTruffleString a, int fromByteIndex, int maxByteIndex, byte[] values, Encoding expectedEncoding,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA) {
            if (isUTF16Or32(expectedEncoding)) {
                throw InternalErrors.illegalArgument("UTF-16 and UTF-32 not supported!");
            }
            a.checkEncoding(expectedEncoding);
            if (a.isEmpty()) {
                return -1;
            }
            a.boundsCheckRaw(fromByteIndex, maxByteIndex);
            if (fromByteIndex == maxByteIndex || TSCodeRange.is7Bit(a.codeRange()) && noneIsAscii(this, values)) {
                return -1;
            }
            assert isStride0(a);
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;
                return TStringOps.indexOfAnyByte(this, a, arrayA, offsetA, fromByteIndex, maxByteIndex, values);
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link ByteIndexOfAnyByteNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static ByteIndexOfAnyByteNode create() {
            return TruffleStringFactory.ByteIndexOfAnyByteNodeGen.create();
        }

        /**
         * Get the uncached version of {@link ByteIndexOfAnyByteNode}.
         *
         * @since 22.1
         */
        public static ByteIndexOfAnyByteNode getUncached() {
            return TruffleStringFactory.ByteIndexOfAnyByteNodeGen.getUncached();
        }
    }

    /**
     * Node to find the index of the first occurrence of any {@code char} from a given array. See
     * {@link #execute(AbstractTruffleString, int, int, char[])} for details.
     *
     * @since 22.1
     */
    public abstract static class CharIndexOfAnyCharUTF16Node extends AbstractPublicNode {

        CharIndexOfAnyCharUTF16Node() {
        }

        /**
         * Return the char index of the first occurrence of any char contained in {@code values},
         * bounded by {@code fromCharIndex} (inclusive) and {@code maxCharIndex} (exclusive).
         * <p>
         * If none of the values is found, return a negative value.
         *
         * @since 22.1
         */
        public abstract int execute(AbstractTruffleString a, int fromCharIndex, int maxCharIndex, char[] values);

        @Specialization
        final int indexOfRaw(AbstractTruffleString a, int fromCharIndex, int maxCharIndex, char[] values,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeNode,
                        @Cached TStringOpsNodes.IndexOfAnyCharUTF16Node indexOfNode) {
            a.checkEncoding(Encoding.UTF_16);
            if (a.isEmpty()) {
                return -1;
            }
            a.boundsCheckRaw(fromCharIndex, maxCharIndex);
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;
                int codeRangeA = getCodeRangeNode.execute(this, a, arrayA, offsetA, Encoding.UTF_16);
                if (fromCharIndex == maxCharIndex || TSCodeRange.isFixedWidth(codeRangeA) && noneInCodeRange(this, codeRangeA, values)) {
                    return -1;
                }
                return indexOfNode.execute(this, a, arrayA, offsetA, fromCharIndex, maxCharIndex, values);
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link CharIndexOfAnyCharUTF16Node}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static CharIndexOfAnyCharUTF16Node create() {
            return TruffleStringFactory.CharIndexOfAnyCharUTF16NodeGen.create();
        }

        /**
         * Get the uncached version of {@link CharIndexOfAnyCharUTF16Node}.
         *
         * @since 22.1
         */
        public static CharIndexOfAnyCharUTF16Node getUncached() {
            return TruffleStringFactory.CharIndexOfAnyCharUTF16NodeGen.getUncached();
        }
    }

    /**
     * Node to find the index of the first occurrence of any {@code int} from a given array. See
     * {@link #execute(AbstractTruffleString, int, int, int[])} for details.
     *
     * @since 22.1
     */
    public abstract static class IntIndexOfAnyIntUTF32Node extends AbstractPublicNode {

        IntIndexOfAnyIntUTF32Node() {
        }

        /**
         * Return the int index of the first occurrence of any int contained in {@code values},
         * bounded by {@code fromIntIndex} (inclusive) and {@code maxIntIndex} (exclusive).
         * <p>
         * If none of the values is found, return a negative value.
         *
         * @since 22.1
         */
        public abstract int execute(AbstractTruffleString a, int fromIntIndex, int maxIntIndex, int[] values);

        @Specialization
        int indexOfRaw(AbstractTruffleString a, int fromIntIndex, int maxIntIndex, int[] values,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringOpsNodes.IndexOfAnyIntNode indexOfNode) {
            a.checkEncoding(Encoding.UTF_32);
            if (a.isEmpty()) {
                return -1;
            }
            a.boundsCheckRaw(fromIntIndex, maxIntIndex);
            if (fromIntIndex == maxIntIndex || noneInCodeRange(this, a.codeRange(), values)) {
                return -1;
            }
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;
                return indexOfNode.execute(this, a, arrayA, offsetA, fromIntIndex, maxIntIndex, values);
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link IntIndexOfAnyIntUTF32Node}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static IntIndexOfAnyIntUTF32Node create() {
            return TruffleStringFactory.IntIndexOfAnyIntUTF32NodeGen.create();
        }

        /**
         * Get the uncached version of {@link IntIndexOfAnyIntUTF32Node}.
         *
         * @since 22.1
         */
        public static IntIndexOfAnyIntUTF32Node getUncached() {
            return TruffleStringFactory.IntIndexOfAnyIntUTF32NodeGen.getUncached();
        }
    }

    private static boolean noneIsAscii(Node location, byte[] values) {
        for (int i = 0; i < values.length; i++) {
            if (Byte.toUnsignedInt(values[i]) <= 0x7f) {
                return false;
            }
            TStringConstants.truffleSafePointPoll(location, i + 1);
        }
        return true;
    }

    private static boolean noneInCodeRange(Node location, int codeRange, char[] values) {
        for (int i = 0; i < values.length; i++) {
            if (TSCodeRange.isInCodeRange(values[i], codeRange)) {
                return false;
            }
            TStringConstants.truffleSafePointPoll(location, i + 1);
        }
        return true;
    }

    private static boolean noneInCodeRange(Node location, int codeRange, int[] values) {
        for (int i = 0; i < values.length; i++) {
            if (TSCodeRange.isInCodeRange(values[i], codeRange)) {
                return false;
            }
            TStringConstants.truffleSafePointPoll(location, i + 1);
        }
        return true;
    }

    /**
     * A set of codepoints in a given encoding. Used in
     * {@link ByteIndexOfCodePointSetNode#execute(AbstractTruffleString, int, int, TruffleString.CodePointSet)}.
     *
     * @since 23.0
     */
    public static final class CodePointSet {

        private final int[] ranges;
        private final Encoding encoding;
        private final IndexOfCodePointSet.IndexOfNode[] indexOfNodes;

        CodePointSet(int[] ranges, Encoding encoding, IndexOfCodePointSet.IndexOfNode[] indexOfNodes) {
            this.ranges = ranges;
            this.encoding = encoding;
            this.indexOfNodes = indexOfNodes;
        }

        /**
         * Creates a new {@link CodePointSet} from the given list of ranges. This operation is
         * expensive, it is recommended to cache the result.
         *
         * @param ranges a sorted list of non-adjacent codepoint ranges. For every two consecutive
         *            array elements, the first is interpreted as the range's inclusive lower bound,
         *            and the second element is the range's inclusive upper bound. Example: an array
         *            {@code [1, 4, 8, 10]} represents the inclusive ranges {@code [1-4]} and
         *            {@code [8-10]}.
         *
         * @since 23.0
         */
        @TruffleBoundary
        public static CodePointSet fromRanges(int[] ranges, Encoding encoding) {
            int[] rangesDefensiveCopy = Arrays.copyOf(ranges, ranges.length);
            return new CodePointSet(rangesDefensiveCopy, encoding, IndexOfCodePointSet.fromRanges(rangesDefensiveCopy, encoding));
        }

        TStringInternalNodes.IndexOfCodePointSetNode createNode() {
            IndexOfCodePointSet.IndexOfNode[] nodesCopy = new IndexOfCodePointSet.IndexOfNode[indexOfNodes.length];
            for (int i = 0; i < indexOfNodes.length; i++) {
                nodesCopy[i] = indexOfNodes[i].shallowCopy();
            }
            return TStringInternalNodesFactory.IndexOfCodePointSetNodeGen.create(nodesCopy, encoding);
        }

        /**
         * Returns {@code true} if {@link ByteIndexOfCodePointSetNode} may implement the search for
         * this particular code point set in strings with the given code range by dispatching to a
         * compiler intrinsic.
         *
         * @since 23.0
         */
        public boolean isIntrinsicCandidate(CodeRange codeRange) {
            for (int i = 0; i < indexOfNodes.length - 1; i++) {
                IndexOfCodePointSet.IndexOfNode node = indexOfNodes[i];
                if (TSCodeRange.ordinal(node.maxCodeRange) >= codeRange.ordinal()) {
                    return node.isFast();
                }
            }
            return indexOfNodes[indexOfNodes.length - 1].isFast();
        }
    }

    /**
     * Node to find the byte index of the first occurrence of a codepoint present in a given
     * codepoint set. See
     * {@link #execute(AbstractTruffleString, int, int, TruffleString.CodePointSet)} for details.
     *
     * @since 23.0
     */
    public abstract static class ByteIndexOfCodePointSetNode extends AbstractPublicNode {

        ByteIndexOfCodePointSetNode() {
        }

        /**
         * Returns the byte index of the first codepoint present in the given {@link CodePointSet},
         * bounded by {@code fromByteIndex} (inclusive) and {@code toByteIndex} (exclusive).
         * <p>
         * {@link ByteIndexOfCodePointSetNode} will specialize on the given {@link CodePointSet}'s
         * content, which is therefore required to be
         * {@link CompilerAsserts#partialEvaluationConstant(Object) partial evaluation constant}.
         * <p>
         * Usage example: A node that scans a string for a known set of code points and escapes them
         * with '\'.
         *
         * <pre>
         * {@code
         * abstract static class StringEscapeNode extends Node {
         *
         *     public static final TruffleString.Encoding ENCODING = TruffleString.Encoding.UTF_32;
         *     public static final TruffleString.ByteIndexOfCodePointSetNode.CodePointSet ESCAPE_CHARS = TruffleString.ByteIndexOfCodePointSetNode.CodePointSet.fromRanges(new int[]{
         *                     '\n', '\n',
         *                     '\r', '\r',
         *                     // ....
         *     }, ENCODING);
         *
         *     abstract TruffleString execute(TruffleString input);
         *
         *     &#64;Specialization
         *     static TruffleString run(TruffleString input,
         *                     &#64;Cached TruffleString.ByteIndexOfCodePointSetNode byteIndexOfCodePointSetNode,
         *                     &#64;Cached TruffleStringBuilder.AppendSubstringByteIndexNode appendSubstringByteIndexNode,
         *                     &#64;Cached TruffleString.CodePointAtByteIndexNode codePointAtByteIndexNode,
         *                     &#64;Cached TruffleStringBuilder.AppendCodePointNode appendCodePointNode,
         *                     &#64;Cached TruffleString.ByteLengthOfCodePointNode byteLengthOfCodePointNode,
         *                     &#64;Cached TruffleStringBuilder.ToStringNode toStringNode) {
         *         int byteLength = input.byteLength(ENCODING);
         *         TruffleStringBuilder sb = TruffleStringBuilder.create(ENCODING, byteLength);
         *         int lastPos = 0;
         *         int pos = 0;
         *         while (pos >= 0) {
         *             pos = byteIndexOfCodePointSetNode.execute(input, lastPos, byteLength, ESCAPE_CHARS);
         *             int substringLength = (pos < 0 ? byteLength : pos) - lastPos;
         *             appendSubstringByteIndexNode.execute(sb, input, lastPos, substringLength);
         *             if (pos >= 0) {
         *                 int codePoint = codePointAtByteIndexNode.execute(input, pos, ENCODING);
         *                 appendCodePointNode.execute(sb, '\\');
         *                 appendCodePointNode.execute(sb, codePoint);
         *                 int codePointLength = byteLengthOfCodePointNode.execute(input, pos, ENCODING);
         *                 lastPos = pos + codePointLength;
         *             }
         *         }
         *         return toStringNode.execute(sb);
         *     }
         * }
         * }
         * </code>
         * </pre>
         *
         * @param codePointSet The set of codepoints to look for. This parameter is expected to be
         *            {@link CompilerAsserts#partialEvaluationConstant(Object) partial evaluation
         *            constant}.
         *
         * @since 23.0
         */
        public final int execute(AbstractTruffleString a, int fromByteIndex, int toByteIndex, CodePointSet codePointSet) {
            return execute(a, fromByteIndex, toByteIndex, codePointSet, true);
        }

        /**
         * Returns the byte index of the first codepoint present in the given {@link CodePointSet},
         * bounded by {@code fromByteIndex} (inclusive) and {@code toByteIndex} (exclusive).
         *
         * @param usePreciseCodeRange If this parameter is set to {@code true}, the node may
         *            evaluate the input string's precise code range for better search performance.
         *            For more details, see {@link GetCodeRangeNode} and
         *            {@link GetCodeRangeImpreciseNode}. This parameter is expected to be
         *            {@link CompilerAsserts#partialEvaluationConstant(Object) partial evaluation
         *            constant}.
         *
         * @since 24.1
         */
        public abstract int execute(AbstractTruffleString a, int fromByteIndex, int toByteIndex, CodePointSet codePointSet, boolean usePreciseCodeRange);

        @Specialization(guards = "codePointSet == cachedCodePointSet", limit = "1", excludeForUncached = true)
        static int indexOfSpecialized(AbstractTruffleString a, int fromByteIndex, int toByteIndex, CodePointSet codePointSet, boolean usePreciseCodeRange,
                        @Bind Node node,
                        @Cached @Exclusive InlinedConditionProfile managedProfileA,
                        @Cached @Exclusive InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.GetPreciseCodeRangeNode getPreciseCodeRangeNode,
                        @Cached(value = "codePointSet") CodePointSet cachedCodePointSet,
                        @Cached("cachedCodePointSet.createNode()") TStringInternalNodes.IndexOfCodePointSetNode internalNode) {
            Encoding encoding = cachedCodePointSet.encoding;
            CompilerAsserts.partialEvaluationConstant(codePointSet);
            CompilerAsserts.partialEvaluationConstant(encoding);
            CompilerAsserts.partialEvaluationConstant(usePreciseCodeRange);
            a.checkEncoding(encoding);
            if (a.isEmpty()) {
                return -1;
            }
            int fromIndex = rawIndex(fromByteIndex, encoding);
            int toIndex = rawIndex(toByteIndex, encoding);
            a.boundsCheckRaw(fromIndex, toIndex);
            if (fromIndex == toIndex) {
                return -1;
            }
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(node, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(node, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(node, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;
                int codeRangeA = usePreciseCodeRange ? getPreciseCodeRangeNode.execute(node, a, arrayA, offsetA, encoding) : a.codeRange();
                return byteIndex(internalNode.execute(arrayA, offsetA, a.length(), a.stride(), codeRangeA, fromIndex, toIndex), encoding);
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        @Specialization(replaces = "indexOfSpecialized")
        int indexOfUncached(AbstractTruffleString a, int fromByteIndex, int toByteIndex, CodePointSet codePointSet, @SuppressWarnings("unused") boolean usePreciseCodeRange,
                        @Cached @Exclusive InlinedConditionProfile managedProfileA,
                        @Cached @Exclusive InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeNode,
                        @Cached TruffleStringIterator.InternalNextNode nextNode) {
            Encoding encoding = codePointSet.encoding;
            CompilerAsserts.partialEvaluationConstant(codePointSet);
            CompilerAsserts.partialEvaluationConstant(encoding);
            a.checkEncoding(encoding);
            if (a.isEmpty()) {
                return -1;
            }
            int fromIndex = rawIndex(fromByteIndex, encoding);
            int toIndex = rawIndex(toByteIndex, encoding);
            a.boundsCheckRaw(fromIndex, toIndex);
            if (fromIndex == toIndex) {
                return -1;
            }
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;
                int codeRangeA = getCodeRangeNode.execute(this, a, arrayA, offsetA, encoding);
                TruffleStringIterator it = forwardIterator(a, arrayA, offsetA, codeRangeA, encoding);
                it.setRawIndex(fromIndex);
                while (it.getRawIndex() < toIndex) {
                    assert it.hasNext();
                    int index = it.getByteIndex();
                    if (IndexOfCodePointSet.IndexOfRangesNode.rangesContain(codePointSet.ranges, nextNode.execute(this, it, encoding))) {
                        return index;
                    }
                }
                return -1;
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link ByteIndexOfCodePointSetNode}.
         *
         * @since 23.0
         */
        @NeverDefault
        public static ByteIndexOfCodePointSetNode create() {
            return TruffleStringFactory.ByteIndexOfCodePointSetNodeGen.create();
        }

        /**
         * Get the uncached version of {@link ByteIndexOfCodePointSetNode}.
         *
         * @since 23.0
         */
        public static ByteIndexOfCodePointSetNode getUncached() {
            return TruffleStringFactory.ByteIndexOfCodePointSetNodeGen.getUncached();
        }
    }

    /**
     * Node to find the index of the first occurrence of a given code point. See
     * {@link #execute(AbstractTruffleString, int, int, int, TruffleString.Encoding)} for details.
     *
     * @since 22.1
     */
    public abstract static class IndexOfCodePointNode extends AbstractPublicNode {

        IndexOfCodePointNode() {
        }

        /**
         * Return the codepoint index of the first occurrence of {@code codepoint}, bounded by
         * {@code fromIndex} (inclusive) and {@code toIndex} (exclusive), if no occurrence is found
         * return a negative value.
         *
         * @since 22.1
         */
        public abstract int execute(AbstractTruffleString a, int codepoint, int fromIndex, int toIndex, Encoding expectedEncoding);

        @Specialization
        final int doIndexOf(AbstractTruffleString a, int codepoint, int fromIndex, int toIndex, Encoding encoding,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.GetCodePointLengthNode getCodePointLengthNode,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeNode,
                        @Cached TStringInternalNodes.IndexOfCodePointNode indexOfNode) {
            a.checkEncoding(encoding);
            if (a.isEmpty()) {
                return -1;
            }
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;
                a.boundsCheck(this, arrayA, offsetA, fromIndex, toIndex, encoding, getCodePointLengthNode);
                int codeRangeA = getCodeRangeNode.execute(this, a, arrayA, offsetA, encoding);
                return indexOfNode.execute(this, a, arrayA, offsetA, codeRangeA, encoding, codepoint, fromIndex, toIndex);
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link IndexOfCodePointNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static IndexOfCodePointNode create() {
            return TruffleStringFactory.IndexOfCodePointNodeGen.create();
        }

        /**
         * Get the uncached version of {@link IndexOfCodePointNode}.
         *
         * @since 22.1
         */
        public static IndexOfCodePointNode getUncached() {
            return TruffleStringFactory.IndexOfCodePointNodeGen.getUncached();
        }
    }

    /**
     * {@link IndexOfCodePointNode}, but with byte indices.
     *
     * @since 22.1
     */
    public abstract static class ByteIndexOfCodePointNode extends AbstractPublicNode {

        ByteIndexOfCodePointNode() {
        }

        /**
         * {@link IndexOfCodePointNode}, but with byte indices.
         *
         * @since 22.1
         */
        public abstract int execute(AbstractTruffleString a, int codepoint, int fromByteIndex, int toByteIndex, Encoding expectedEncoding);

        @Specialization
        final int doIndexOf(AbstractTruffleString a, int codepoint, int fromByteIndex, int toByteIndex, Encoding encoding,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeNode,
                        @Cached TStringInternalNodes.IndexOfCodePointRawNode indexOfNode) {
            a.checkEncoding(encoding);
            if (a.isEmpty()) {
                return -1;
            }
            final int fromIndex = rawIndex(fromByteIndex, encoding);
            final int toIndex = rawIndex(toByteIndex, encoding);
            a.boundsCheckRaw(fromIndex, toIndex);
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;
                int codeRangeA = getCodeRangeNode.execute(this, a, arrayA, offsetA, encoding);
                return byteIndex(indexOfNode.execute(this, a, arrayA, offsetA, codeRangeA, encoding, codepoint, fromIndex, toIndex), encoding);
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link ByteIndexOfCodePointNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static ByteIndexOfCodePointNode create() {
            return TruffleStringFactory.ByteIndexOfCodePointNodeGen.create();
        }

        /**
         * Get the uncached version of {@link ByteIndexOfCodePointNode}.
         *
         * @since 22.1
         */
        public static ByteIndexOfCodePointNode getUncached() {
            return TruffleStringFactory.ByteIndexOfCodePointNodeGen.getUncached();
        }
    }

    /**
     * Node to find the codepoint index of the last occurrence of a given code point. See
     * {@link #execute(AbstractTruffleString, int, int, int, TruffleString.Encoding)} for details.
     *
     * @since 22.1
     */
    public abstract static class LastIndexOfCodePointNode extends AbstractPublicNode {

        LastIndexOfCodePointNode() {
        }

        /**
         * Return the codepoint index of the last occurrence of {@code codepoint}, bounded by
         * {@code fromIndex} (exclusive upper limit) and {@code toIndex} (inclusive lower limit), if
         * no occurrence is found return a negative value.
         *
         * @since 22.1
         */
        public abstract int execute(AbstractTruffleString a, int codepoint, int fromIndex, int toIndex, Encoding expectedEncoding);

        @Specialization
        final int doIndexOf(AbstractTruffleString a, int codepoint, int fromIndex, int toIndex, Encoding encoding,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.GetCodePointLengthNode getCodePointLengthNode,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeNode,
                        @Cached TStringInternalNodes.LastIndexOfCodePointNode lastIndexOfNode) {
            a.checkEncoding(encoding);
            if (a.isEmpty()) {
                return -1;
            }
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;
                a.boundsCheck(this, arrayA, offsetA, toIndex, fromIndex, encoding, getCodePointLengthNode);
                int codeRangeA = getCodeRangeNode.execute(this, a, arrayA, offsetA, encoding);
                return lastIndexOfNode.execute(this, a, arrayA, offsetA, codeRangeA, encoding, codepoint, fromIndex, toIndex);
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link LastIndexOfCodePointNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static LastIndexOfCodePointNode create() {
            return TruffleStringFactory.LastIndexOfCodePointNodeGen.create();
        }

        /**
         * Get the uncached version of {@link LastIndexOfCodePointNode}.
         *
         * @since 22.1
         */
        public static LastIndexOfCodePointNode getUncached() {
            return TruffleStringFactory.LastIndexOfCodePointNodeGen.getUncached();
        }
    }

    /**
     * {@link LastIndexOfCodePointNode}, but with byte indices.
     *
     * @since 22.1
     */
    public abstract static class LastByteIndexOfCodePointNode extends AbstractPublicNode {

        LastByteIndexOfCodePointNode() {
        }

        /**
         * {@link LastIndexOfCodePointNode}, but with byte indices.
         *
         * @since 22.1
         */
        public abstract int execute(AbstractTruffleString a, int codepoint, int fromByteIndex, int toByteIndex, Encoding expectedEncoding);

        @Specialization
        final int doIndexOf(AbstractTruffleString a, int codepoint, int fromByteIndex, int toByteIndex, Encoding encoding,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeNode,
                        @Cached TStringInternalNodes.LastIndexOfCodePointRawNode lastIndexOfNode) {
            a.checkEncoding(encoding);
            if (a.isEmpty()) {
                return -1;
            }
            final int fromIndex = rawIndex(fromByteIndex, encoding);
            final int toIndex = rawIndex(toByteIndex, encoding);
            a.boundsCheckRaw(toIndex, fromIndex);
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;
                int codeRangeA = getCodeRangeNode.execute(this, a, arrayA, offsetA, encoding);
                return byteIndex(lastIndexOfNode.execute(this, a, arrayA, offsetA, codeRangeA, encoding, codepoint, fromIndex, toIndex), encoding);
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link LastByteIndexOfCodePointNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static LastByteIndexOfCodePointNode create() {
            return TruffleStringFactory.LastByteIndexOfCodePointNodeGen.create();
        }

        /**
         * Get the uncached version of {@link LastByteIndexOfCodePointNode}.
         *
         * @since 22.1
         */
        public static LastByteIndexOfCodePointNode getUncached() {
            return TruffleStringFactory.LastByteIndexOfCodePointNodeGen.getUncached();
        }
    }

    /**
     * Node to find the index of the first occurrence of a given string. See
     * {@link #execute(AbstractTruffleString, AbstractTruffleString, int, int, TruffleString.Encoding)}
     * for details.
     *
     * @since 22.1
     */
    public abstract static class IndexOfStringNode extends AbstractPublicNode {

        IndexOfStringNode() {
        }

        /**
         * Return the codepoint index of the first occurrence of {@code string}, bounded by
         * {@code fromIndex} (inclusive) and {@code toIndex} (exclusive), if no occurrence is found
         * return a negative value.
         *
         * @since 22.1
         */
        public abstract int execute(AbstractTruffleString a, AbstractTruffleString b, int fromIndex, int toIndex, Encoding expectedEncoding);

        @Specialization
        final int indexOfString(AbstractTruffleString a, AbstractTruffleString b, int fromIndex, int toIndex, Encoding encoding,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached InlinedConditionProfile managedProfileB,
                        @Cached InlinedConditionProfile nativeProfileB,
                        @Cached TStringInternalNodes.GetCodePointLengthNode getCodePointLengthANode,
                        @Cached TStringInternalNodes.GetCodePointLengthNode getCodePointLengthBNode,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeANode,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeBNode,
                        @Cached TStringInternalNodes.InternalIndexOfStringNode indexOfStringNode) {
            Object dataA = a.data();
            Object dataB = b.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                final byte[] arrayB;
                final long addOffsetB;
                if (managedProfileB.profile(this, dataB instanceof byte[])) {
                    arrayB = (byte[]) dataB;
                    addOffsetB = byteArrayBaseOffset();
                } else if (nativeProfileB.profile(this, dataB instanceof NativePointer)) {
                    arrayB = null;
                    addOffsetB = NativePointer.unwrap(dataB);
                } else {
                    arrayB = b.materializeLazy(this, dataB);
                    addOffsetB = byteArrayBaseOffset();
                }
                final long offsetB = b.offset() + addOffsetB;

                int codeRangeA = getCodeRangeANode.execute(this, a, arrayA, offsetA, encoding);
                int codeRangeB = getCodeRangeBNode.execute(this, b, arrayB, offsetB, encoding);
                a.looseCheckEncoding(encoding, codeRangeA);
                b.looseCheckEncoding(encoding, codeRangeB);
                if (b.isEmpty()) {
                    return fromIndex;
                }
                if (a.isEmpty()) {
                    return -1;
                }
                a.boundsCheck(this, arrayA, offsetA, fromIndex, toIndex, encoding, getCodePointLengthANode);
                if (indexOfCannotMatch(this, codeRangeA, b, arrayB, offsetB, codeRangeB, toIndex - fromIndex, encoding, getCodePointLengthBNode)) {
                    return -1;
                }
                return indexOfStringNode.execute(this, a, arrayA, offsetA, codeRangeA, b, arrayB, offsetB, codeRangeB, fromIndex, toIndex, encoding);
            } finally {
                Reference.reachabilityFence(dataA);
                Reference.reachabilityFence(dataB);
            }
        }

        /**
         * Create a new {@link IndexOfStringNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static IndexOfStringNode create() {
            return TruffleStringFactory.IndexOfStringNodeGen.create();
        }

        /**
         * Get the uncached version of {@link IndexOfStringNode}.
         *
         * @since 22.1
         */
        public static IndexOfStringNode getUncached() {
            return TruffleStringFactory.IndexOfStringNodeGen.getUncached();
        }
    }

    /**
     * {@link IndexOfStringNode}, but with byte indices.
     *
     * @since 22.1
     */
    public abstract static class ByteIndexOfStringNode extends AbstractPublicNode {

        ByteIndexOfStringNode() {
        }

        /**
         * {@link IndexOfStringNode}, but with byte indices.
         *
         * @since 22.1
         */
        public final int execute(AbstractTruffleString a, AbstractTruffleString b, int fromByteIndex, int toByteIndex, Encoding expectedEncoding) {
            return execute(a, b, fromByteIndex, toByteIndex, null, expectedEncoding);
        }

        /**
         * {@link IndexOfStringNode}, but with byte indices. This variant accepts a
         * {@link TruffleString.WithMask} as the search value {@code b}, which changes the searching
         * algorithm in the following manner: whenever the contents of {@code a} and {@code b} are
         * compared, the mask is OR'ed to {@code a}, as shown in this exemplary method:
         *
         * <pre>
         * {@code
         * boolean bytesEqualAt(TruffleString a, int byteIndexA, TruffleString.WithMask b, int byteIndexB) {
         *     return (readByte(a, byteIndexA) | readByte(b.mask, byteIndexB)) == readByte(b, byteIndexB);
         * }
         * }
         * </pre>
         *
         * @since 22.1
         */
        public final int execute(AbstractTruffleString a, WithMask b, int fromByteIndex, int toByteIndex, Encoding expectedEncoding) {
            return execute(a, b.string, fromByteIndex, toByteIndex, b.mask, expectedEncoding);
        }

        abstract int execute(AbstractTruffleString a, AbstractTruffleString b, int fromByteIndex, int toByteIndex, byte[] mask, Encoding expectedEncoding);

        @Specialization
        final int indexOfString(AbstractTruffleString a, AbstractTruffleString b, int fromByteIndex, int toByteIndex, byte[] mask, Encoding encoding,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached InlinedConditionProfile managedProfileB,
                        @Cached InlinedConditionProfile nativeProfileB,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeANode,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeBNode,
                        @Cached TStringInternalNodes.IndexOfStringRawNode indexOfStringNode) {
            Object dataA = a.data();
            Object dataB = b.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                final byte[] arrayB;
                final long addOffsetB;
                if (managedProfileB.profile(this, dataB instanceof byte[])) {
                    arrayB = (byte[]) dataB;
                    addOffsetB = byteArrayBaseOffset();
                } else if (nativeProfileB.profile(this, dataB instanceof NativePointer)) {
                    arrayB = null;
                    addOffsetB = NativePointer.unwrap(dataB);
                } else {
                    arrayB = b.materializeLazy(this, dataB);
                    addOffsetB = byteArrayBaseOffset();
                }
                final long offsetB = b.offset() + addOffsetB;

                final int codeRangeA = getCodeRangeANode.execute(this, a, arrayA, offsetA, encoding);
                final int codeRangeB = getCodeRangeBNode.execute(this, b, arrayB, offsetB, encoding);
                a.looseCheckEncoding(encoding, codeRangeA);
                b.looseCheckEncoding(encoding, codeRangeB);
                if (JCodings.JCODINGS_ENABLED && mask != null && !isSupportedEncodingWithCompaction(encoding) && !isFixedWidth(codeRangeA)) {
                    throw InternalErrors.unsupportedOperation();
                }
                if (b.isEmpty()) {
                    return fromByteIndex;
                }
                if (a.isEmpty()) {
                    return -1;
                }
                final int fromIndex = rawIndex(fromByteIndex, encoding);
                final int toIndex = rawIndex(toByteIndex, encoding);
                a.boundsCheckRaw(fromIndex, toIndex);
                if (indexOfCannotMatch(codeRangeA, b, codeRangeB, mask, toIndex - fromIndex)) {
                    return -1;
                }
                return byteIndex(indexOfStringNode.execute(this, a, arrayA, offsetA, codeRangeA, b, arrayB, offsetB, codeRangeB, fromIndex, toIndex, mask, encoding), encoding);
            } finally {
                Reference.reachabilityFence(dataA);
                Reference.reachabilityFence(dataB);
            }
        }

        /**
         * Create a new {@link ByteIndexOfStringNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static ByteIndexOfStringNode create() {
            return TruffleStringFactory.ByteIndexOfStringNodeGen.create();
        }

        /**
         * Get the uncached version of {@link ByteIndexOfStringNode}.
         *
         * @since 22.1
         */
        public static ByteIndexOfStringNode getUncached() {
            return TruffleStringFactory.ByteIndexOfStringNodeGen.getUncached();
        }
    }

    /**
     * Node to find the index of the last occurrence of a given string. See
     * {@link #execute(AbstractTruffleString, AbstractTruffleString, int, int, TruffleString.Encoding)}
     * for details.
     *
     * @since 22.1
     */
    public abstract static class LastIndexOfStringNode extends AbstractPublicNode {

        LastIndexOfStringNode() {
        }

        /**
         * Return the codepoint index of the last occurrence of {@code string}, bounded by
         * {@code fromIndex} (exclusive upper limit) and {@code toIndex} (inclusive lower limit), if
         * no occurrence is found return a negative value.
         *
         * @since 22.1
         */
        public abstract int execute(AbstractTruffleString a, AbstractTruffleString b, int fromIndex, int toIndex, Encoding expectedEncoding);

        @Specialization
        final int lastIndexOfString(AbstractTruffleString a, AbstractTruffleString b, int fromIndex, int toIndex, Encoding encoding,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached InlinedConditionProfile managedProfileB,
                        @Cached InlinedConditionProfile nativeProfileB,
                        @Cached TStringInternalNodes.GetCodePointLengthNode getCodePointLengthANode,
                        @Cached TStringInternalNodes.GetCodePointLengthNode getCodePointLengthBNode,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeANode,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeBNode,
                        @Cached TStringInternalNodes.LastIndexOfStringNode indexOfStringNode) {
            Object dataA = a.data();
            Object dataB = b.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                final byte[] arrayB;
                final long addOffsetB;
                if (managedProfileB.profile(this, dataB instanceof byte[])) {
                    arrayB = (byte[]) dataB;
                    addOffsetB = byteArrayBaseOffset();
                } else if (nativeProfileB.profile(this, dataB instanceof NativePointer)) {
                    arrayB = null;
                    addOffsetB = NativePointer.unwrap(dataB);
                } else {
                    arrayB = b.materializeLazy(this, dataB);
                    addOffsetB = byteArrayBaseOffset();
                }
                final long offsetB = b.offset() + addOffsetB;

                final int codeRangeA = getCodeRangeANode.execute(this, a, arrayA, offsetA, encoding);
                final int codeRangeB = getCodeRangeBNode.execute(this, b, arrayB, offsetB, encoding);
                a.looseCheckEncoding(encoding, codeRangeA);
                b.looseCheckEncoding(encoding, codeRangeB);
                if (b.isEmpty()) {
                    return fromIndex;
                }
                if (a.isEmpty()) {
                    return -1;
                }
                a.boundsCheck(this, arrayA, offsetA, toIndex, fromIndex, encoding, getCodePointLengthANode);
                if (indexOfCannotMatch(this, codeRangeA, b, arrayB, offsetB, codeRangeB, fromIndex - toIndex, encoding, getCodePointLengthBNode)) {
                    return -1;
                }
                return indexOfStringNode.execute(this, a, arrayA, offsetA, codeRangeA, b, arrayB, offsetB, codeRangeB, fromIndex, toIndex, encoding);
            } finally {
                Reference.reachabilityFence(dataA);
                Reference.reachabilityFence(dataB);
            }
        }

        /**
         * Create a new {@link LastIndexOfStringNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static LastIndexOfStringNode create() {
            return TruffleStringFactory.LastIndexOfStringNodeGen.create();
        }

        /**
         * Get the uncached version of {@link LastIndexOfStringNode}.
         *
         * @since 22.1
         */
        public static LastIndexOfStringNode getUncached() {
            return TruffleStringFactory.LastIndexOfStringNodeGen.getUncached();
        }
    }

    /**
     * {@link LastIndexOfStringNode}, but with byte indices.
     *
     * @since 22.1
     */
    public abstract static class LastByteIndexOfStringNode extends AbstractPublicNode {

        LastByteIndexOfStringNode() {
        }

        /**
         * {@link LastIndexOfStringNode}, but with byte indices.
         *
         * @since 22.1
         */
        public final int execute(AbstractTruffleString a, AbstractTruffleString b, int fromIndex, int toIndex, Encoding expectedEncoding) {
            return execute(a, b, fromIndex, toIndex, null, expectedEncoding);
        }

        /**
         * {@link LastIndexOfStringNode}, but with byte indices. This variant accepts a
         * {@link TruffleString.WithMask} as the search value {@code b}, which changes the searching
         * algorithm in the following manner: whenever the contents of {@code a} and {@code b} are
         * compared, the mask is OR'ed to {@code a}, as shown in this exemplary method:
         *
         * <pre>
         * {@code
         * boolean bytesEqualAt(TruffleString a, int byteIndexA, TruffleString.WithMask b, int byteIndexB) {
         *     return (readByte(a, byteIndexA) | readByte(b.mask, byteIndexB)) == readByte(b, byteIndexB);
         * }
         * }
         * </pre>
         *
         * @since 22.1
         */
        public final int execute(AbstractTruffleString a, WithMask b, int fromIndex, int toIndex, Encoding expectedEncoding) {
            return execute(a, b.string, fromIndex, toIndex, b.mask, expectedEncoding);
        }

        abstract int execute(AbstractTruffleString a, AbstractTruffleString b, int fromIndex, int toIndex, byte[] mask, Encoding expectedEncoding);

        @Specialization
        final int lastByteIndexOfString(AbstractTruffleString a, AbstractTruffleString b, int fromIndexB, int toIndexB, byte[] mask, Encoding encoding,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached InlinedConditionProfile managedProfileB,
                        @Cached InlinedConditionProfile nativeProfileB,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeANode,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeBNode,
                        @Cached TStringInternalNodes.LastIndexOfStringRawNode indexOfStringNode) {
            Object dataA = a.data();
            Object dataB = b.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                final byte[] arrayB;
                final long addOffsetB;
                if (managedProfileB.profile(this, dataB instanceof byte[])) {
                    arrayB = (byte[]) dataB;
                    addOffsetB = byteArrayBaseOffset();
                } else if (nativeProfileB.profile(this, dataB instanceof NativePointer)) {
                    arrayB = null;
                    addOffsetB = NativePointer.unwrap(dataB);
                } else {
                    arrayB = b.materializeLazy(this, dataB);
                    addOffsetB = byteArrayBaseOffset();
                }
                final long offsetB = b.offset() + addOffsetB;

                final int codeRangeA = getCodeRangeANode.execute(this, a, arrayA, offsetA, encoding);
                final int codeRangeB = getCodeRangeBNode.execute(this, b, arrayB, offsetB, encoding);
                a.looseCheckEncoding(encoding, codeRangeA);
                b.looseCheckEncoding(encoding, codeRangeB);
                if (JCodings.JCODINGS_ENABLED && mask != null && isUnsupportedEncoding(encoding) && !isFixedWidth(codeRangeA)) {
                    throw InternalErrors.unsupportedOperation();
                }
                if (b.isEmpty()) {
                    return fromIndexB;
                }
                if (a.isEmpty()) {
                    return -1;
                }
                final int fromIndex = rawIndex(fromIndexB, encoding);
                final int toIndex = rawIndex(toIndexB, encoding);
                a.boundsCheckRaw(toIndex, fromIndex);
                if (indexOfCannotMatch(codeRangeA, b, codeRangeB, mask, fromIndex - toIndex)) {
                    return -1;
                }
                return byteIndex(indexOfStringNode.execute(this, a, arrayA, offsetA, codeRangeA, b, arrayB, offsetB, codeRangeB, fromIndex, toIndex, mask, encoding), encoding);
            } finally {
                Reference.reachabilityFence(dataA);
                Reference.reachabilityFence(dataB);
            }
        }

        /**
         * Create a new {@link LastByteIndexOfStringNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static LastByteIndexOfStringNode create() {
            return TruffleStringFactory.LastByteIndexOfStringNodeGen.create();
        }

        /**
         * Get the uncached version of {@link LastByteIndexOfStringNode}.
         *
         * @since 22.1
         */
        public static LastByteIndexOfStringNode getUncached() {
            return TruffleStringFactory.LastByteIndexOfStringNodeGen.getUncached();
        }
    }

    /**
     * Node to compare two strings byte-by-byte. See
     * {@link #execute(AbstractTruffleString, AbstractTruffleString, TruffleString.Encoding)} for
     * details.
     *
     * @since 22.1
     */
    public abstract static class CompareBytesNode extends AbstractPublicNode {

        CompareBytesNode() {
        }

        /**
         * Compare strings {@code a} and {@code b} byte-by-byte. Returns zero if {@code a} and
         * {@code b} are equal. If {@code a} is equal to {@code b} up to its length, but {@code b}
         * is longer than {@code a}, a negative value is returned. In the inverse case, a positive
         * value is returned. Otherwise, elements {@code a[i]} and {@code b[i]} at a byte index
         * {@code i} are different. If {@code a[i]} is greater than {@code b[i]}, a positive value
         * is returned, otherwise a negative value is returned.
         *
         * @since 22.1
         */
        public abstract int execute(AbstractTruffleString a, AbstractTruffleString b, Encoding expectedEncoding);

        @Specialization
        int compare(AbstractTruffleString a, AbstractTruffleString b, Encoding expectedEncoding,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached InlinedConditionProfile managedProfileB,
                        @Cached InlinedConditionProfile nativeProfileB) {
            a.looseCheckEncoding(expectedEncoding, a.codeRange());
            b.looseCheckEncoding(expectedEncoding, b.codeRange());
            Object dataA = a.data();
            Object dataB = b.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                final byte[] arrayB;
                final long addOffsetB;
                if (managedProfileB.profile(this, dataB instanceof byte[])) {
                    arrayB = (byte[]) dataB;
                    addOffsetB = byteArrayBaseOffset();
                } else if (nativeProfileB.profile(this, dataB instanceof NativePointer)) {
                    arrayB = null;
                    addOffsetB = NativePointer.unwrap(dataB);
                } else {
                    arrayB = b.materializeLazy(this, dataB);
                    addOffsetB = byteArrayBaseOffset();
                }
                final long offsetB = b.offset() + addOffsetB;

                if ((a.stride() | b.stride()) == 0 && !a.isEmpty() && !b.isEmpty()) {
                    int cmp = TStringOps.readS0(arrayA, offsetA, a.length(), 0) - TStringOps.readS0(arrayB, offsetB, b.length(), 0);
                    if (cmp != 0) {
                        return cmp;
                    }
                }
                if (a == b) {
                    return 0;
                }
                return TStringOpsNodes.memcmpBytes(this, a, arrayA, offsetA, b, arrayB, offsetB);
            } finally {
                Reference.reachabilityFence(dataA);
                Reference.reachabilityFence(dataB);
            }
        }

        /**
         * Create a new {@link CompareBytesNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static CompareBytesNode create() {
            return TruffleStringFactory.CompareBytesNodeGen.create();
        }

        /**
         * Get the uncached version of {@link CompareBytesNode}.
         *
         * @since 22.1
         */
        public static CompareBytesNode getUncached() {
            return TruffleStringFactory.CompareBytesNodeGen.getUncached();
        }
    }

    /**
     * Node to compare two UTF-16 strings. See
     * {@link #execute(AbstractTruffleString, AbstractTruffleString)} for details.
     *
     * @since 22.1
     */
    public abstract static class CompareCharsUTF16Node extends AbstractPublicNode {

        CompareCharsUTF16Node() {
        }

        /**
         * Compare UTF-16 strings {@code a} and {@code b} char-by-char. Returns zero if {@code a}
         * and {@code b} are equal. If {@code a} is equal to {@code b} up to its length, but
         * {@code b} is longer than {@code a}, a negative value is returned. In the inverse case, a
         * positive value is returned. Otherwise, elements {@code a[i]} and {@code b[i]} at an index
         * {@code i} are different. If {@code a[i]} is greater than {@code b[i]}, a positive value
         * is returned, otherwise a negative value is returned.
         *
         * @since 22.1
         */
        public abstract int execute(AbstractTruffleString a, AbstractTruffleString b);

        @Specialization
        int compare(AbstractTruffleString a, AbstractTruffleString b,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached InlinedConditionProfile managedProfileB,
                        @Cached InlinedConditionProfile nativeProfileB) {
            a.looseCheckEncoding(Encoding.UTF_16, a.codeRange());
            b.looseCheckEncoding(Encoding.UTF_16, b.codeRange());
            Object dataA = a.data();
            Object dataB = b.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                final byte[] arrayB;
                final long addOffsetB;
                if (managedProfileB.profile(this, dataB instanceof byte[])) {
                    arrayB = (byte[]) dataB;
                    addOffsetB = byteArrayBaseOffset();
                } else if (nativeProfileB.profile(this, dataB instanceof NativePointer)) {
                    arrayB = null;
                    addOffsetB = NativePointer.unwrap(dataB);
                } else {
                    arrayB = b.materializeLazy(this, dataB);
                    addOffsetB = byteArrayBaseOffset();
                }
                final long offsetB = b.offset() + addOffsetB;

                if ((a.stride() | b.stride()) == 0 && !a.isEmpty() && !b.isEmpty()) {
                    int cmp = TStringOps.readS0(arrayA, offsetA, a.length(), 0) - TStringOps.readS0(arrayB, offsetB, b.length(), 0);
                    if (cmp != 0) {
                        return cmp;
                    }
                }
                if (a == b) {
                    return 0;
                }
                return TStringOpsNodes.memcmp(this, a, arrayA, offsetA, b, arrayB, offsetB);
            } finally {
                Reference.reachabilityFence(dataA);
                Reference.reachabilityFence(dataB);
            }
        }

        /**
         * Create a new {@link CompareCharsUTF16Node}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static CompareCharsUTF16Node create() {
            return TruffleStringFactory.CompareCharsUTF16NodeGen.create();
        }

        /**
         * Get the uncached version of {@link CompareCharsUTF16Node}.
         *
         * @since 22.1
         */
        public static CompareCharsUTF16Node getUncached() {
            return TruffleStringFactory.CompareCharsUTF16NodeGen.getUncached();
        }
    }

    /**
     * Node to compare two UTF-32 strings. See
     * {@link #execute(AbstractTruffleString, AbstractTruffleString)} for details.
     *
     * @since 22.1
     */
    public abstract static class CompareIntsUTF32Node extends AbstractPublicNode {

        CompareIntsUTF32Node() {
        }

        /**
         * Compare UTF-32 strings {@code a} and {@code b} int-by-int. Returns zero if {@code a} and
         * {@code b} are equal. If {@code a} is equal to {@code b} up to its length, but {@code b}
         * is longer than {@code a}, a negative value is returned. In the inverse case, a positive
         * value is returned. Otherwise, elements {@code a[i]} and {@code b[i]} at an index
         * {@code i} are different. If {@code a[i]} is greater than {@code b[i]}, a positive value
         * is returned, otherwise a negative value is returned.
         *
         * @since 22.1
         */
        public abstract int execute(AbstractTruffleString a, AbstractTruffleString b);

        @Specialization
        int compare(AbstractTruffleString a, AbstractTruffleString b,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached InlinedConditionProfile managedProfileB,
                        @Cached InlinedConditionProfile nativeProfileB) {
            a.looseCheckEncoding(Encoding.UTF_32, a.codeRange());
            b.looseCheckEncoding(Encoding.UTF_32, b.codeRange());
            Object dataA = a.data();
            Object dataB = b.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                final byte[] arrayB;
                final long addOffsetB;
                if (managedProfileB.profile(this, dataB instanceof byte[])) {
                    arrayB = (byte[]) dataB;
                    addOffsetB = byteArrayBaseOffset();
                } else if (nativeProfileB.profile(this, dataB instanceof NativePointer)) {
                    arrayB = null;
                    addOffsetB = NativePointer.unwrap(dataB);
                } else {
                    arrayB = b.materializeLazy(this, dataB);
                    addOffsetB = byteArrayBaseOffset();
                }
                final long offsetB = b.offset() + addOffsetB;

                if ((a.stride() | b.stride()) == 0 && !a.isEmpty() && !b.isEmpty()) {
                    int cmp = TStringOps.readS0(arrayA, offsetA, a.length(), 0) - TStringOps.readS0(arrayB, offsetB, b.length(), 0);
                    if (cmp != 0) {
                        return cmp;
                    }
                }
                if (a == b) {
                    return 0;
                }
                return TStringOpsNodes.memcmp(this, a, arrayA, offsetA, b, arrayB, offsetB);
            } finally {
                Reference.reachabilityFence(dataA);
                Reference.reachabilityFence(dataB);
            }
        }

        /**
         * Create a new {@link CompareIntsUTF32Node}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static CompareIntsUTF32Node create() {
            return TruffleStringFactory.CompareIntsUTF32NodeGen.create();
        }

        /**
         * Get the uncached version of {@link CompareIntsUTF32Node}.
         *
         * @since 22.1
         */
        public static CompareIntsUTF32Node getUncached() {
            return TruffleStringFactory.CompareIntsUTF32NodeGen.getUncached();
        }
    }

    /**
     * Node to check codepoint equality of two string regions. See
     * {@link #execute(AbstractTruffleString, int, AbstractTruffleString, int, int, TruffleString.Encoding)}.
     *
     * @since 22.1
     */
    public abstract static class RegionEqualNode extends AbstractPublicNode {

        RegionEqualNode() {
        }

        /**
         * Checks for codepoint equality in a region with the given codepoint index and codepoint
         * length.
         * <p>
         * Equivalent to:
         *
         * <pre>
         * for (int i = 0; i &lt; length; i++) {
         *     if (codePointAt(a, fromIndexA + i) != codePointAt(b, fromIndexB + i)) {
         *         return false;
         *     }
         * }
         * return true;
         * </pre>
         *
         * @since 22.1
         */
        public abstract boolean execute(AbstractTruffleString a, int fromIndexA, AbstractTruffleString b, int fromIndexB, int length, Encoding expectedEncoding);

        @Specialization
        final boolean regionEquals(AbstractTruffleString a, int fromIndexA, AbstractTruffleString b, int fromIndexB, int length, Encoding encoding,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached InlinedConditionProfile managedProfileB,
                        @Cached InlinedConditionProfile nativeProfileB,
                        @Cached TStringInternalNodes.GetCodePointLengthNode getCodePointLengthANode,
                        @Cached TStringInternalNodes.GetCodePointLengthNode getCodePointLengthBNode,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeANode,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeBNode,
                        @Cached TStringInternalNodes.RegionEqualsNode regionEqualsNode) {
            if (length == 0) {
                return true;
            }
            Object dataA = a.data();
            Object dataB = b.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                final byte[] arrayB;
                final long addOffsetB;
                if (managedProfileB.profile(this, dataB instanceof byte[])) {
                    arrayB = (byte[]) dataB;
                    addOffsetB = byteArrayBaseOffset();
                } else if (nativeProfileB.profile(this, dataB instanceof NativePointer)) {
                    arrayB = null;
                    addOffsetB = NativePointer.unwrap(dataB);
                } else {
                    arrayB = b.materializeLazy(this, dataB);
                    addOffsetB = byteArrayBaseOffset();
                }
                final long offsetB = b.offset() + addOffsetB;

                final int codeRangeA = getCodeRangeANode.execute(this, a, arrayA, offsetA, encoding);
                final int codeRangeB = getCodeRangeBNode.execute(this, b, arrayB, offsetB, encoding);
                a.looseCheckEncoding(encoding, codeRangeA);
                b.looseCheckEncoding(encoding, codeRangeB);
                a.boundsCheckRegion(this, arrayA, offsetA, fromIndexA, length, encoding, getCodePointLengthANode);
                b.boundsCheckRegion(this, arrayB, offsetB, fromIndexB, length, encoding, getCodePointLengthBNode);
                return regionEqualsNode.execute(this, a, arrayA, offsetA, codeRangeA, fromIndexA, b, arrayB, offsetB, codeRangeB, fromIndexB, length, encoding);
            } finally {
                Reference.reachabilityFence(dataA);
                Reference.reachabilityFence(dataB);
            }
        }

        /**
         * Create a new {@link RegionEqualNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static RegionEqualNode create() {
            return TruffleStringFactory.RegionEqualNodeGen.create();
        }

        /**
         * Get the uncached version of {@link RegionEqualNode}.
         *
         * @since 22.1
         */
        public static RegionEqualNode getUncached() {
            return TruffleStringFactory.RegionEqualNodeGen.getUncached();
        }
    }

    /**
     * Node to check for a region match, byte-by-byte. See
     * {@link #execute(AbstractTruffleString, int, AbstractTruffleString, int, int, TruffleString.Encoding)}
     * and
     * {@link #execute(AbstractTruffleString, int, TruffleString.WithMask, int, int, TruffleString.Encoding)}
     * for details.
     *
     * @since 22.1
     */
    public abstract static class RegionEqualByteIndexNode extends AbstractPublicNode {

        RegionEqualByteIndexNode() {
        }

        /**
         * Checks for a region match, byte-by-byte.
         *
         * @since 22.1
         */
        public final boolean execute(AbstractTruffleString a, int fromByteIndexA, AbstractTruffleString b, int fromByteIndexB, int length, Encoding expectedEncoding) {
            return execute(a, fromByteIndexA, b, fromByteIndexB, length, null, expectedEncoding);
        }

        /**
         * Checks for a region match, byte-by-byte. This variant accepts a
         * {@link TruffleString.WithMask} as the search value {@code b}, which changes the matching
         * algorithm in the following manner: when the contents of {@code a} and {@code b} are
         * compared, the mask is OR'ed to {@code a}, as shown in this exemplary method:
         *
         * <pre>
         * {@code
         * boolean bytesEqualAt(TruffleString a, int byteIndexA, TruffleString.WithMask b, int byteIndexB) {
         *     return (readByte(a, byteIndexA) | readByte(b.mask, byteIndexB)) == readByte(b, byteIndexB);
         * }
         * }
         * </pre>
         *
         * @since 22.1
         */
        public final boolean execute(AbstractTruffleString a, int fromByteIndexA, WithMask b, int fromByteIndexB, int length, Encoding expectedEncoding) {
            return execute(a, fromByteIndexA, b.string, fromByteIndexB, length, b.mask, expectedEncoding);
        }

        abstract boolean execute(AbstractTruffleString a, int fromIndexA, AbstractTruffleString b, int fromIndexB, int length, byte[] mask, Encoding expectedEncoding);

        @Specialization
        boolean regionEquals(AbstractTruffleString a, int byteFromIndexA, AbstractTruffleString b, int byteFromIndexB, int byteLength, byte[] mask, Encoding expectedEncoding,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached InlinedConditionProfile managedProfileB,
                        @Cached InlinedConditionProfile nativeProfileB) {
            if (byteLength == 0) {
                return true;
            }
            a.looseCheckEncoding(expectedEncoding, a.codeRange());
            b.looseCheckEncoding(expectedEncoding, b.codeRange());
            final int fromIndexA = rawIndex(byteFromIndexA, expectedEncoding);
            final int fromIndexB = rawIndex(byteFromIndexB, expectedEncoding);
            final int length = rawIndex(byteLength, expectedEncoding);
            a.boundsCheckRegionRaw(fromIndexA, length);
            b.boundsCheckRegionRaw(fromIndexB, length);
            Object dataA = a.data();
            Object dataB = b.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                final byte[] arrayB;
                final long addOffsetB;
                if (managedProfileB.profile(this, dataB instanceof byte[])) {
                    arrayB = (byte[]) dataB;
                    addOffsetB = byteArrayBaseOffset();
                } else if (nativeProfileB.profile(this, dataB instanceof NativePointer)) {
                    arrayB = null;
                    addOffsetB = NativePointer.unwrap(dataB);
                } else {
                    arrayB = b.materializeLazy(this, dataB);
                    addOffsetB = byteArrayBaseOffset();
                }
                final long offsetB = b.offset() + addOffsetB;

                return TStringOps.regionEqualsWithOrMaskWithStride(this, a, arrayA, offsetA, a.stride(), fromIndexA, b, arrayB, offsetB, b.stride(), fromIndexB, mask, length);
            } finally {
                Reference.reachabilityFence(dataA);
                Reference.reachabilityFence(dataB);
            }
        }

        /**
         * Create a new {@link RegionEqualByteIndexNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static RegionEqualByteIndexNode create() {
            return TruffleStringFactory.RegionEqualByteIndexNodeGen.create();
        }

        /**
         * Get the uncached version of {@link RegionEqualByteIndexNode}.
         *
         * @since 22.1
         */
        public static RegionEqualByteIndexNode getUncached() {
            return TruffleStringFactory.RegionEqualByteIndexNodeGen.getUncached();
        }
    }

    /**
     * Node to concatenate two strings. See
     * {@link #execute(AbstractTruffleString, AbstractTruffleString, TruffleString.Encoding, boolean)}
     * for details.
     *
     * @since 22.1
     */
    public abstract static class ConcatNode extends AbstractPublicNode {

        ConcatNode() {
        }

        /**
         * Create a new string by concatenating {@code a} and {@code b}.
         *
         * @param lazy if {@code true}, the creation of the new string's internal array may be
         *            delayed until it is required by another operation. This parameter is expected
         *            to be {@link CompilerAsserts#partialEvaluationConstant(boolean) partial
         *            evaluation constant}.
         * @since 22.1
         */
        public abstract TruffleString execute(AbstractTruffleString a, AbstractTruffleString b, Encoding expectedEncoding, boolean lazy);

        @Specialization(guards = "isEmpty(a)")
        static TruffleString aEmpty(@SuppressWarnings("unused") AbstractTruffleString a, TruffleString b, Encoding expectedEncoding, boolean lazy) {
            CompilerAsserts.partialEvaluationConstant(lazy);
            if (AbstractTruffleString.DEBUG_STRICT_ENCODING_CHECKS) {
                b.looseCheckEncoding(expectedEncoding, b.codeRange());
                return b.switchEncodingUncached(expectedEncoding);
            }
            b.checkEncoding(expectedEncoding);
            return b;
        }

        @Specialization(guards = "isEmpty(a)")
        TruffleString aEmptyMutable(@SuppressWarnings("unused") AbstractTruffleString a, MutableTruffleString b, Encoding expectedEncoding, boolean lazy,
                        @Shared("attributesNode") @Cached TStringInternalNodes.FromBufferWithStringCompactionKnownAttributesNode attributesNode) {
            CompilerAsserts.partialEvaluationConstant(lazy);
            if (AbstractTruffleString.DEBUG_STRICT_ENCODING_CHECKS) {
                b.looseCheckEncoding(expectedEncoding, b.codeRange());
                return b.switchEncodingUncached(expectedEncoding);
            }
            return attributesNode.execute(this, b, expectedEncoding);
        }

        @Specialization(guards = "isEmpty(b)")
        static TruffleString bEmpty(TruffleString a, @SuppressWarnings("unused") AbstractTruffleString b, Encoding expectedEncoding, boolean lazy) {
            CompilerAsserts.partialEvaluationConstant(lazy);
            if (AbstractTruffleString.DEBUG_STRICT_ENCODING_CHECKS) {
                a.looseCheckEncoding(expectedEncoding, a.codeRange());
                return a.switchEncodingUncached(expectedEncoding);
            }
            a.checkEncoding(expectedEncoding);
            return a;
        }

        @Specialization(guards = "isEmpty(b)")
        static TruffleString bEmptyMutable(MutableTruffleString a, @SuppressWarnings("unused") AbstractTruffleString b, Encoding expectedEncoding, boolean lazy,
                        @Bind Node node,
                        @Shared("attributesNode") @Cached TStringInternalNodes.FromBufferWithStringCompactionKnownAttributesNode attributesNode) {
            CompilerAsserts.partialEvaluationConstant(lazy);
            if (AbstractTruffleString.DEBUG_STRICT_ENCODING_CHECKS) {
                a.looseCheckEncoding(expectedEncoding, a.codeRange());
                return a.switchEncodingUncached(expectedEncoding);
            }
            return attributesNode.execute(node, a, expectedEncoding);
        }

        @Specialization(guards = {"!isEmpty(a)", "!isEmpty(b)"})
        static TruffleString doConcat(AbstractTruffleString a, AbstractTruffleString b, Encoding encoding, boolean lazy,
                        @Bind Node node,
                        @Cached TStringInternalNodes.GetPreciseCodeRangeWithMaterializationNode getCodeRangeANode,
                        @Cached TStringInternalNodes.GetPreciseCodeRangeWithMaterializationNode getCodeRangeBNode,
                        @Cached TStringInternalNodes.StrideFromCodeRangeNode getStrideNode,
                        @Cached TStringInternalNodes.ConcatEagerNode concatEagerNode,
                        @Cached AsTruffleStringNode asTruffleStringANode,
                        @Cached AsTruffleStringNode asTruffleStringBNode,
                        @Cached InlinedBranchProfile outOfMemoryProfile,
                        @Cached InlinedConditionProfile lazyProfile) {
            CompilerAsserts.partialEvaluationConstant(lazy);
            final int codeRangeA = getCodeRangeANode.execute(node, a, encoding);
            final int codeRangeB = getCodeRangeBNode.execute(node, b, encoding);
            a.looseCheckEncoding(encoding, codeRangeA);
            b.looseCheckEncoding(encoding, codeRangeB);
            int commonCodeRange = TSCodeRange.commonCodeRange(codeRangeA, codeRangeB);
            assert !(isBrokenMultiByte(codeRangeA) || isBrokenMultiByte(codeRangeB)) || isBrokenMultiByte(commonCodeRange);
            int targetStride = getStrideNode.execute(node, commonCodeRange, encoding);
            int length = addByteLengths(node, a, b, targetStride, outOfMemoryProfile);
            boolean valid = !isBrokenMultiByte(commonCodeRange);
            if (lazyProfile.profile(node, lazy && valid && (a.isImmutable() || b.isImmutable()) && (length << targetStride) >= TStringConstants.LAZY_CONCAT_MIN_LENGTH)) {
                if (AbstractTruffleString.DEBUG_STRICT_ENCODING_CHECKS) {
                    return TruffleString.createLazyConcat(asTruffleStringLoose(a, encoding), asTruffleStringLoose(b, encoding), encoding, length, targetStride, commonCodeRange);
                } else {
                    return TruffleString.createLazyConcat(asTruffleStringANode.execute(a, encoding), asTruffleStringBNode.execute(b, encoding), encoding, length, targetStride, commonCodeRange);
                }
            }
            return concatEagerNode.execute(node, a, b, encoding, length, targetStride, commonCodeRange);
        }

        static int addByteLengths(Node node, AbstractTruffleString a, AbstractTruffleString b, int targetStride, InlinedBranchProfile outOfMemoryProfile) {
            long length = (long) a.length() + (long) b.length();
            if (length << targetStride > TStringConstants.MAX_ARRAY_SIZE) {
                outOfMemoryProfile.enter(node);
                throw InternalErrors.outOfMemory();
            }
            return (int) length;
        }

        private static TruffleString asTruffleStringLoose(AbstractTruffleString a, Encoding encoding) {
            if (a.isImmutable()) {
                return (TruffleString) a;
            }
            return TStringInternalNodes.FromBufferWithStringCompactionKnownAttributesNode.getUncached().execute(
                            TStringInternalNodes.FromBufferWithStringCompactionKnownAttributesNode.getUncached(), a, encoding);
        }

        /**
         * Create a new {@link ConcatNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static ConcatNode create() {
            return TruffleStringFactory.ConcatNodeGen.create();
        }

        /**
         * Get the uncached version of {@link ConcatNode}.
         *
         * @since 22.1
         */
        public static ConcatNode getUncached() {
            return TruffleStringFactory.ConcatNodeGen.getUncached();
        }
    }

    /**
     * Node to repeat a given string {@code N} times. See
     * {@link #execute(AbstractTruffleString, int, TruffleString.Encoding)} for details.
     *
     * @since 22.1
     */
    public abstract static class RepeatNode extends AbstractPublicNode {

        RepeatNode() {
        }

        /**
         * Create a new string by repeating {@code n} times string {@code a}.
         *
         * @since 22.1
         */
        public abstract TruffleString execute(AbstractTruffleString a, int n, Encoding expectedEncoding);

        @Specialization
        final TruffleString repeat(AbstractTruffleString a, int n, Encoding expectedEncoding,
                        @Cached AsTruffleStringNode asTruffleStringNode,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.GetPreciseCodeRangeNode getPreciseCodeRangeNode,
                        @Cached TStringInternalNodes.GetCodePointLengthNode getCodePointLengthNode,
                        @Cached TStringInternalNodes.CalcStringAttributesNode calcStringAttributesNode,
                        @Cached InlinedConditionProfile brokenProfile,
                        @Cached InlinedBranchProfile outOfMemoryProfile,
                        @Cached InlinedBranchProfile compactProfile) {
            a.checkEncoding(expectedEncoding);
            if (n < 0) {
                throw InternalErrors.illegalArgument("n must be positive (was: %d)", n);
            }
            if (a.isEmpty() || n == 0) {
                return expectedEncoding.getEmpty();
            }
            if (n == 1) {
                return asTruffleStringNode.execute(a, expectedEncoding);
            }
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                int codeRangeA = getPreciseCodeRangeNode.execute(this, a, arrayA, offsetA, expectedEncoding);
                int codePointLengthA = getCodePointLengthNode.execute(this, a, arrayA, offsetA, expectedEncoding);
                int byteLengthA = (a.length()) << a.stride();
                int stride = Stride.fromCodeRange(codeRangeA, expectedEncoding);
                long byteLength = (((long) a.length()) << stride) * n;
                if (Long.compareUnsigned(byteLength, TStringConstants.MAX_ARRAY_SIZE) > 0) {
                    outOfMemoryProfile.enter(this);
                    throw InternalErrors.outOfMemory();
                }
                byte[] array = new byte[(int) byteLength];
                int offsetB = byteArrayBaseOffset();
                if (stride == a.stride()) {
                    for (int i = 0; i < n; i++) {
                        TStringOps.arraycopyWithStride(this, arrayA, offsetA, 0, 0, array, offsetB, 0, 0, byteLengthA);
                        offsetB += byteLengthA;
                        TStringConstants.truffleSafePointPoll(this, i + 1);
                    }
                } else {
                    compactProfile.enter(this);
                    int byteLengthCompact = a.length() << stride;
                    for (int i = 0; i < n; i++) {
                        TStringOps.arraycopyWithStride(this, arrayA, offsetA, a.stride(), 0, array, offsetB, stride, 0, a.length());
                        offsetB += byteLengthCompact;
                        TStringConstants.truffleSafePointPoll(this, i + 1);
                    }
                }
                int length = (int) (byteLength >> stride);
                if (brokenProfile.profile(this, isBroken(codeRangeA))) {
                    long attrs = calcStringAttributesNode.execute(this, null, array, byteArrayBaseOffset(), length, stride, expectedEncoding, 0, codeRangeA);
                    codeRangeA = StringAttributes.getCodeRange(attrs);
                    codePointLengthA = StringAttributes.getCodePointLength(attrs);
                } else {
                    codePointLengthA *= n;
                }
                return createFromByteArray(array, length, stride, expectedEncoding, codePointLengthA, codeRangeA);
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link RepeatNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static RepeatNode create() {
            return TruffleStringFactory.RepeatNodeGen.create();
        }

        /**
         * Get the uncached version of {@link RepeatNode}.
         *
         * @since 22.1
         */
        public static RepeatNode getUncached() {
            return TruffleStringFactory.RepeatNodeGen.getUncached();
        }
    }

    /**
     * Node to create a substring of a given string. See
     * {@link #execute(AbstractTruffleString, int, int, TruffleString.Encoding, boolean)} for
     * details.
     *
     * @since 22.1
     */
    public abstract static class SubstringNode extends AbstractPublicNode {

        SubstringNode() {
        }

        /**
         * Create a substring of {@code a}, starting from codepoint index {@code fromIndex}, with
         * codepoint length {@code length}. If {@code lazy} is {@code true}, {@code a}'s internal
         * storage will be re-used instead of creating a copy of the requested range. Since the
         * resulting string will have a reference to {@code a}'s internal storage, and
         * {@link TruffleString} currently does <i>not</i> resize/trim the substring's internal
         * storage at any point, the {@code lazy} variant effectively creates a memory leak! The
         * caller is responsible for deciding whether this is acceptable or not.
         *
         * @since 22.1
         */
        public abstract TruffleString execute(AbstractTruffleString a, int fromIndex, int length, Encoding expectedEncoding, boolean lazy);

        @Specialization
        final TruffleString substring(AbstractTruffleString a, int fromIndex, int length, Encoding encoding, boolean lazy,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeANode,
                        @Cached TStringInternalNodes.GetCodePointLengthNode getCodePointLengthNode,
                        @Cached TStringInternalNodes.CodePointIndexToRawNode translateIndexNode,
                        @Cached TStringInternalNodes.SubstringNode substringNode) {
            a.checkEncoding(encoding);
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                a.boundsCheckRegion(this, arrayA, offsetA, fromIndex, length, encoding, getCodePointLengthNode);
                if (length == 0) {
                    return encoding.getEmpty();
                }
                final int codeRangeA = getCodeRangeANode.execute(this, a, arrayA, offsetA, encoding);
                int fromIndexRaw = translateIndexNode.execute(this, a, arrayA, offsetA, codeRangeA, encoding, 0, fromIndex, false);
                int lengthRaw = translateIndexNode.execute(this, a, arrayA, offsetA, codeRangeA, encoding, fromIndexRaw, length, true);
                return substringNode.execute(this, a, arrayA, offsetA, codeRangeA, encoding, fromIndexRaw, lengthRaw, lazy && a.isImmutable());
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link SubstringNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static SubstringNode create() {
            return TruffleStringFactory.SubstringNodeGen.create();
        }

        /**
         * Get the uncached version of {@link SubstringNode}.
         *
         * @since 22.1
         */
        public static SubstringNode getUncached() {
            return TruffleStringFactory.SubstringNodeGen.getUncached();
        }
    }

    /**
     * {@link SubstringNode}, but with byte indices.
     *
     * @since 22.1
     */
    public abstract static class SubstringByteIndexNode extends AbstractPublicNode {

        SubstringByteIndexNode() {
        }

        /**
         * {@link SubstringNode}, but with byte indices.
         *
         * @since 22.1
         */
        public abstract TruffleString execute(AbstractTruffleString a, int fromByteIndex, int byteLength, Encoding expectedEncoding, boolean lazy);

        @Specialization(guards = "byteLength == 0")
        static TruffleString substringEmpty(AbstractTruffleString a, int fromByteIndex, @SuppressWarnings("unused") int byteLength, Encoding expectedEncoding,
                        @SuppressWarnings("unused") boolean lazy) {
            a.checkEncoding(expectedEncoding);
            final int fromIndex = rawIndex(fromByteIndex, expectedEncoding);
            a.boundsCheckRegionRaw(fromIndex, 0);
            return expectedEncoding.getEmpty();
        }

        @Fallback
        final TruffleString substringRaw(AbstractTruffleString a, int fromByteIndex, int byteLength, Encoding expectedEncoding, boolean lazy,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.SubstringNode substringNode) {
            assert byteLength != 0 : byteLength;
            a.checkEncoding(expectedEncoding);
            final int fromIndex = rawIndex(fromByteIndex, expectedEncoding);
            final int length = rawIndex(byteLength, expectedEncoding);
            a.boundsCheckRegionRaw(fromIndex, length);
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                return substringNode.execute(this, a, arrayA, offsetA, a.codeRange(), expectedEncoding, fromIndex, length, lazy && a.isImmutable());
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link SubstringByteIndexNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static SubstringByteIndexNode create() {
            return TruffleStringFactory.SubstringByteIndexNodeGen.create();
        }

        /**
         * Get the uncached version of {@link SubstringByteIndexNode}.
         *
         * @since 22.1
         */
        public static SubstringByteIndexNode getUncached() {
            return TruffleStringFactory.SubstringByteIndexNodeGen.getUncached();
        }
    }

    /**
     * Node to check two strings for equality.
     * <p>
     * The {@link TruffleString#equals(Object)}-method delegates to this node.
     *
     * @since 22.1
     */
    public abstract static class EqualNode extends AbstractPublicNode {

        EqualNode() {
        }

        /**
         * Returns {@code true} if {@code a} and {@code b} are byte-by-byte equal when considered in
         * {@code expectedEncoding}. Note that this method requires both strings to be compatible to
         * the {@code expectedEncoding}, just like all other operations with an
         * {@code expectedEncoding} parameter!
         * <p>
         * The {@link TruffleString#equals(Object)}-method delegates to this method.
         *
         * @since 22.1
         */
        public abstract boolean execute(AbstractTruffleString a, AbstractTruffleString b, Encoding expectedEncoding);

        @SuppressWarnings("unused")
        @Specialization(guards = "a == b")
        static boolean sameObject(AbstractTruffleString a, AbstractTruffleString b, Encoding expectedEncoding) {
            return true;
        }

        @Fallback
        static boolean check(AbstractTruffleString a, AbstractTruffleString b, Encoding expectedEncoding,
                        @Bind Node node,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached InlinedConditionProfile managedProfileB,
                        @Cached InlinedConditionProfile nativeProfileB,
                        @Cached InlinedConditionProfile lengthAndCodeRangeCheckProfile,
                        @Cached InlinedBranchProfile compareHashProfile,
                        @Cached InlinedConditionProfile checkFirstByteProfile) {
            a.looseCheckEncoding(expectedEncoding, a.codeRange());
            b.looseCheckEncoding(expectedEncoding, b.codeRange());
            return checkContentEquals(node, a, b, managedProfileA, nativeProfileA, managedProfileB, nativeProfileB, lengthAndCodeRangeCheckProfile, compareHashProfile, checkFirstByteProfile);
        }

        static boolean checkContentEquals(
                        Node node,
                        AbstractTruffleString a,
                        AbstractTruffleString b,
                        InlinedConditionProfile managedProfileA,
                        InlinedConditionProfile nativeProfileA,
                        InlinedConditionProfile managedProfileB,
                        InlinedConditionProfile nativeProfileB,
                        InlinedConditionProfile lengthAndCodeRangeCheckProfile,
                        InlinedBranchProfile compareHashProfile,
                        InlinedConditionProfile checkFirstByteProfile) {
            int codeRangeA = a.codeRange();
            int codeRangeB = b.codeRange();
            int lengthCMP = a.length();
            if (lengthAndCodeRangeCheckProfile.profile(node, lengthCMP != b.length() ||
                            TSCodeRange.isPrecise(codeRangeA, codeRangeB) && codeRangeA != codeRangeB)) {
                return false;
            }
            if (a.isHashCodeCalculated() && b.isHashCodeCalculated()) {
                compareHashProfile.enter(node);
                if (a.getHashCodeUnsafe() != b.getHashCodeUnsafe()) {
                    return false;
                }
            }
            if (lengthCMP == 0) {
                return true;
            }
            Object dataA = a.data();
            Object dataB = b.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(node, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(node, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    if (dataA instanceof LazyLong lazyLongA && dataB instanceof LazyLong lazyLongB) {
                        return lazyLongA.value == lazyLongB.value;
                    }
                    arrayA = a.materializeLazy(node, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                final byte[] arrayB;
                final long addOffsetB;
                if (managedProfileB.profile(node, dataB instanceof byte[])) {
                    arrayB = (byte[]) dataB;
                    addOffsetB = byteArrayBaseOffset();
                } else if (nativeProfileB.profile(node, dataB instanceof NativePointer)) {
                    arrayB = null;
                    addOffsetB = NativePointer.unwrap(dataB);
                } else {
                    arrayB = b.materializeLazy(node, dataB);
                    addOffsetB = byteArrayBaseOffset();
                }
                final long offsetB = b.offset() + addOffsetB;

                int strideA = a.stride();
                int strideB = b.stride();
                if (checkFirstByteProfile.profile(node, (strideA | strideB) == 0)) {
                    // fast path: check first byte
                    if (TStringOps.readS0(arrayA, offsetA, a.length(), 0) != TStringOps.readS0(arrayB, offsetB, b.length(), 0)) {
                        return false;
                    } else if (lengthCMP == 1) {
                        return true;
                    }
                }
                return TStringOps.regionEqualsWithOrMaskWithStride(node,
                                a, arrayA, offsetA, strideA, 0,
                                b, arrayB, offsetB, strideB, 0, null, lengthCMP);
            } finally {
                Reference.reachabilityFence(dataA);
                Reference.reachabilityFence(dataB);
            }
        }

        /**
         * A copy of {@link #checkContentEquals} but without profiles. Keep in sync.
         */
        static boolean checkContentEqualsUncached(AbstractTruffleString a, AbstractTruffleString b) {
            Node node = TruffleString.EqualNode.getUncached();
            int codeRangeA = a.codeRange();
            int codeRangeB = b.codeRange();
            int lengthCMP = a.length();
            if (lengthCMP != b.length() ||
                            (TSCodeRange.isPrecise(codeRangeA, codeRangeB) && codeRangeA != codeRangeB)) {
                return false;
            }
            if (a.isHashCodeCalculated() && b.isHashCodeCalculated()) {
                if (a.getHashCodeUnsafe() != b.getHashCodeUnsafe()) {
                    return false;
                }
            }
            if (lengthCMP == 0) {
                return true;
            }
            Object dataA = a.data();
            Object dataB = b.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (dataA instanceof byte[]) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (dataA instanceof NativePointer) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    if (dataA instanceof LazyLong lazyLongA && dataB instanceof LazyLong lazyLongB) {
                        return lazyLongA.value == lazyLongB.value;
                    }
                    arrayA = a.materializeLazy(node, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                final byte[] arrayB;
                final long addOffsetB;
                if (dataB instanceof byte[]) {
                    arrayB = (byte[]) dataB;
                    addOffsetB = byteArrayBaseOffset();
                } else if (dataB instanceof NativePointer) {
                    arrayB = null;
                    addOffsetB = NativePointer.unwrap(dataB);
                } else {
                    arrayB = b.materializeLazy(node, dataB);
                    addOffsetB = byteArrayBaseOffset();
                }
                final long offsetB = b.offset() + addOffsetB;

                int strideA = a.stride();
                int strideB = b.stride();
                if ((strideA | strideB) == 0) {
                    // fast path: check first byte
                    if (TStringOps.readS0(arrayA, offsetA, a.length(), 0) != TStringOps.readS0(arrayB, offsetB, b.length(), 0)) {
                        return false;
                    } else if (lengthCMP == 1) {
                        return true;
                    }
                }
                return TStringOps.regionEqualsWithOrMaskWithStride(node,
                                a, arrayA, offsetA, strideA, 0,
                                b, arrayB, offsetB, strideB, 0, null, lengthCMP);
            } finally {
                Reference.reachabilityFence(dataA);
                Reference.reachabilityFence(dataB);
            }
        }

        /**
         * Create a new {@link EqualNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static EqualNode create() {
            return TruffleStringFactory.EqualNodeGen.create();
        }

        /**
         * Get the uncached version of {@link EqualNode}.
         *
         * @since 22.1
         */
        public static EqualNode getUncached() {
            return TruffleStringFactory.EqualNodeGen.getUncached();
        }
    }

    /**
     * This exception may be thrown by {@link ParseIntNode}, {@link ParseLongNode} or
     * {@link ParseDoubleNode} to indicate that the given string cannot be parsed as an integer,
     * long or double value. This exception does not record stack traces for performance reasons.
     *
     * @since 22.1
     */
    @SuppressWarnings("serial")
    public static final class NumberFormatException extends Exception {

        private static final long serialVersionUID = 0x016db657faff57a2L;

        /**
         * All {@link NumberFormatException}s contain one of the following exception reason values,
         * which may be used to build custom error messages in language implementations.
         *
         * @since 22.3
         */
        enum Reason {

            /**
             * The string was empty, or contained no digits.
             */
            EMPTY("no digits found"),

            /**
             * An invalid codepoint was encountered during parsing.
             */
            INVALID_CODEPOINT("invalid codepoint"),

            /**
             * A '+' or '-' sign without any subsequent digits was encountered.
             */
            LONE_SIGN("lone '+' or '-'"),

            /**
             * The parsed number was too large to fit in an {@code int}/{@code long}.
             */
            OVERFLOW("overflow"),

            /**
             * Invalid codepoints encountered when parsing a hex number.
             */
            MALFORMED_HEX_ESCAPE("malformed hex escape sequence"),

            /**
             * Multiple decimal points encountered.
             */
            MULTIPLE_DECIMAL_POINTS("multiple decimal points"),

            /**
             * The given radix is unsupported.
             */
            UNSUPPORTED_RADIX("unsupported radix");

            private final String message;

            Reason(String message) {
                this.message = message;
            }

            /**
             * Returns a short error description.
             *
             * @since 22.3
             */
            public String getMessage() {
                return message;
            }
        }

        private final AbstractTruffleString string;
        private final int regionOffset;
        private final int regionLength;
        private final Reason reason;

        NumberFormatException(AbstractTruffleString string, Reason reason) {
            this(string, -1, -1, reason);
        }

        NumberFormatException(AbstractTruffleString string, int regionOffset, int regionLength, Reason reason) {
            super();
            this.string = string;
            this.regionOffset = regionOffset;
            this.regionLength = regionLength;
            this.reason = reason;
        }

        /**
         * Returns the {@link Reason} for this exception. Use this to build custom error messages.
         */
        Reason getReason() {
            return reason;
        }

        /**
         * Returns the string that was attempted to parse.
         */
        AbstractTruffleString getString() {
            return string;
        }

        /**
         * Returns the byte offset to error region, or -1 if not applicable.
         */
        int getRegionByteOffset() {
            return regionOffset < 0 ? regionOffset : regionOffset << string.stride();
        }

        /**
         * Returns the error region's length in bytes, or -1 if not applicable.
         */
        int getRegionByteLength() {
            return regionLength < 0 ? regionLength : regionLength << string.stride();
        }

        /**
         * Returns a detailed error message. Not designed to be used on fast paths.
         *
         * @since 22.3
         */
        @TruffleBoundary
        @Override
        public String getMessage() {
            StringBuilder sb = new StringBuilder();
            sb.append("error parsing \"").append(getString()).append("\": ");
            sb.append(getReason().message);
            if (regionOffset >= 0) {
                if (regionLength == 1) {
                    sb.append(" at byte index ").append(getRegionByteOffset());
                } else {
                    sb.append(" from byte index ").append(getRegionByteOffset()).append(" to ").append(getRegionByteOffset() + getRegionByteLength());
                }
            }
            return sb.toString();
        }

        /**
         * No stack trace for this exception.
         *
         * @since 22.1
         */
        @SuppressWarnings("sync-override")
        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }

    /**
     * This exception is thrown when any operation tries to create a {@link Encoding#UTF_16 UTF-16}
     * or {@link Encoding#UTF_32 UTF-32} string with an invalid byte length (not a multiple of 2/4
     * bytes).
     *
     * @since 22.3
     */
    public static final class IllegalByteArrayLengthException extends IllegalArgumentException {

        private static final long serialVersionUID = 0x27d918e593fcf85aL;

        IllegalByteArrayLengthException(String msg) {
            super(msg);
        }
    }

    /**
     * Node to parse a given string as an int value.
     *
     * @since 22.1
     */
    public abstract static class ParseIntNode extends AbstractPublicNode {

        ParseIntNode() {
        }

        /**
         * Parse the given string as an int value, or throw {@link NumberFormatException}.
         *
         * @since 22.1
         */
        public abstract int execute(AbstractTruffleString a, int radix) throws NumberFormatException;

        @Specialization(guards = {"a.isLazyLong()", "radix == 10"})
        final int doLazyLong(AbstractTruffleString a, @SuppressWarnings("unused") int radix,
                        @Cached InlinedBranchProfile errorProfile) throws NumberFormatException {
            long value = ((LazyLong) a.data()).value;
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                errorProfile.enter(this);
                throw NumberConversion.numberFormatException(a, NumberFormatException.Reason.OVERFLOW);
            }
            return (int) value;
        }

        @Fallback
        final int doParse(AbstractTruffleString a, int radix,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.GetPreciseCodeRangeNode getCodeRangeANode,
                        @Cached TStringInternalNodes.ParseIntNode parseIntNode,
                        @Cached InlinedIntValueProfile radixProfile) throws NumberFormatException {
            assert !a.isLazyLong() || radix != 10;
            Encoding encodingA = Encoding.get(a.encoding());
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                final int codeRangeA = getCodeRangeANode.execute(this, a, arrayA, offsetA, encodingA);
                return parseIntNode.execute(this, a, arrayA, offsetA, codeRangeA, encodingA, radixProfile.profile(this, radix));
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link ParseIntNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static ParseIntNode create() {
            return TruffleStringFactory.ParseIntNodeGen.create();
        }

        /**
         * Get the uncached version of {@link ParseIntNode}.
         *
         * @since 22.1
         */
        public static ParseIntNode getUncached() {
            return TruffleStringFactory.ParseIntNodeGen.getUncached();
        }
    }

    /**
     * Node to parse a given string as a long value.
     *
     * @since 22.1
     */
    public abstract static class ParseLongNode extends AbstractPublicNode {

        ParseLongNode() {
        }

        /**
         * Parse the given string as a long value, or throw {@link NumberFormatException}.
         *
         * @since 22.1
         */
        public abstract long execute(AbstractTruffleString a, int radix) throws TruffleString.NumberFormatException;

        @Specialization(guards = {"a.isLazyLong()", "radix == 10"})
        static long doLazyLong(AbstractTruffleString a, @SuppressWarnings("unused") int radix) {
            return ((LazyLong) a.data()).value;
        }

        @Fallback
        final long doParse(AbstractTruffleString a, int radix,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.GetPreciseCodeRangeNode getCodeRangeANode,
                        @Cached TStringInternalNodes.ParseLongNode parseLongNode,
                        @Cached InlinedIntValueProfile radixProfile) throws NumberFormatException {
            assert !a.isLazyLong() || radix != 10;
            Encoding encodingA = Encoding.get(a.encoding());
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                final int codeRangeA = getCodeRangeANode.execute(this, a, arrayA, offsetA, encodingA);
                return parseLongNode.execute(this, a, arrayA, offsetA, codeRangeA, encodingA, radixProfile.profile(this, radix));
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link ParseLongNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static ParseLongNode create() {
            return TruffleStringFactory.ParseLongNodeGen.create();
        }

        /**
         * Get the uncached version of {@link ParseLongNode}.
         *
         * @since 22.1
         */
        public static ParseLongNode getUncached() {
            return TruffleStringFactory.ParseLongNodeGen.getUncached();
        }
    }

    /**
     * Node to parse a given string as a double value.
     *
     * @since 22.1
     */
    public abstract static class ParseDoubleNode extends AbstractPublicNode {

        ParseDoubleNode() {
        }

        /**
         * Parse the given string as a double value, or throw {@link NumberFormatException}.
         *
         * @since 22.1
         */
        public abstract double execute(AbstractTruffleString a) throws NumberFormatException;

        @Specialization(guards = "isLazyLongSafeInteger(a)")
        static double doLazyLong(AbstractTruffleString a) {
            return ((LazyLong) a.data()).value;
        }

        @Fallback
        final double parseDouble(AbstractTruffleString a,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.ParseDoubleNode parseDoubleNode) throws NumberFormatException {
            assert !isLazyLongSafeInteger(a);
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                return parseDoubleNode.execute(this, a, arrayA, offsetA);
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        static boolean isLazyLongSafeInteger(AbstractTruffleString a) {
            return a.isLazyLong() && NumberConversion.isSafeInteger(((LazyLong) a.data()).value);
        }

        /**
         * Create a new {@link ParseDoubleNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static ParseDoubleNode create() {
            return TruffleStringFactory.ParseDoubleNodeGen.create();
        }

        /**
         * Get the uncached version of {@link ParseDoubleNode}.
         *
         * @since 22.1
         */
        public static ParseDoubleNode getUncached() {
            return TruffleStringFactory.ParseDoubleNodeGen.getUncached();
        }
    }

    /**
     * Node to get a string's internal byte array. See
     * {@link #execute(AbstractTruffleString, TruffleString.Encoding)} for details.
     *
     * @since 22.1
     */
    public abstract static class GetInternalByteArrayNode extends AbstractPublicNode {

        GetInternalByteArrayNode() {
        }

        /**
         * Get the given string's internal byte array. The returned byte array must not be modified.
         * Note that this operation may also return a copy of the string's internal storage, if the
         * internal format does not match the regular encoded string format; compacted and native
         * strings will always yield a copy.
         *
         * CAUTION: TruffleString re-uses internal byte arrays whenever possible, DO NOT modify the
         * arrays returned by this operation. Use this operation only when absolutely necessary.
         * Reading a string's contents should always be done via nodes like {@link ReadByteNode},
         * {@link ReadCharUTF16Node}, {@link CodePointAtIndexNode}, {@link CodePointAtByteIndexNode}
         * etc., if at all possible. If mutability is required, use {@link MutableTruffleString}
         * instead.
         *
         * @since 22.1
         */
        public abstract InternalByteArray execute(AbstractTruffleString a, Encoding expectedEncoding);

        @Specialization
        InternalByteArray getInternalByteArray(AbstractTruffleString a, Encoding expectedEncoding,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached InlinedConditionProfile utf16Profile,
                        @Cached InlinedConditionProfile utf16S0Profile,
                        @Cached InlinedConditionProfile utf32Profile,
                        @Cached InlinedConditionProfile utf32S0Profile,
                        @Cached InlinedConditionProfile utf32S1Profile,
                        @Cached InlinedConditionProfile isByteArrayProfile) {
            if (a.isEmpty()) {
                return InternalByteArray.EMPTY;
            }
            a.checkEncoding(expectedEncoding);
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                if (utf16Profile.profile(this, isUTF16(expectedEncoding))) {
                    if (utf16S0Profile.profile(this, isStride0(a))) {
                        return inflate(a, arrayA, offsetA, 0, 1);
                    }
                } else if (utf32Profile.profile(this, isUTF32(expectedEncoding))) {
                    if (utf32S0Profile.profile(this, isStride0(a))) {
                        return inflate(a, arrayA, offsetA, 0, 2);
                    }
                    if (utf32S1Profile.profile(this, isStride1(a))) {
                        return inflate(a, arrayA, offsetA, 1, 2);
                    }
                }
                int byteLength = a.length() << a.stride();
                if (isByteArrayProfile.profile(this, arrayA != null)) {
                    return new InternalByteArray(arrayA, a.offset(), byteLength);
                } else {
                    return new InternalByteArray(TStringOps.arraycopyOfWithStride(this, arrayA, offsetA, byteLength, 0, byteLength, 0), 0, byteLength);
                }
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        private InternalByteArray inflate(AbstractTruffleString a, byte[] dataA, long offsetA, int strideA, int strideB) {
            assert a.stride() == strideA;
            CompilerAsserts.partialEvaluationConstant(strideA);
            CompilerAsserts.partialEvaluationConstant(strideB);
            return new InternalByteArray(TStringOps.arraycopyOfWithStride(this, dataA, offsetA, a.length(), strideA, a.length(), strideB), 0, a.length() << strideB);
        }

        /**
         * Create a new {@link GetInternalByteArrayNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static GetInternalByteArrayNode create() {
            return TruffleStringFactory.GetInternalByteArrayNodeGen.create();
        }

        /**
         * Get the uncached version of {@link GetInternalByteArrayNode}.
         *
         * @since 22.1
         */
        public static GetInternalByteArrayNode getUncached() {
            return TruffleStringFactory.GetInternalByteArrayNodeGen.getUncached();
        }
    }

    /**
     * Node to get a {@link AbstractTruffleString#isNative() native} string's pointer object. See
     * {@link #execute(AbstractTruffleString, TruffleString.Encoding)} for details.
     *
     * @since 22.1
     */
    public abstract static class GetInternalNativePointerNode extends AbstractPublicNode {

        GetInternalNativePointerNode() {
        }

        /**
         * Get the given string's pointer object which was passed to {@link FromNativePointerNode}.
         * If the string is not backed by a native pointer, this node will throw an
         * {@link UnsupportedOperationException}. Use {@link AbstractTruffleString#isNative()} to
         * check whether the string is actually backed by a native pointer before calling this node.
         * Caution: If the given string is a {@link TruffleString}, the native pointer must not be
         * modified as long as the string is used.
         *
         * @since 22.1
         */
        public abstract Object execute(AbstractTruffleString a, Encoding expectedEncoding);

        @Specialization
        static Object getNativePointer(AbstractTruffleString a, Encoding expectedEncoding) {
            a.checkEncoding(expectedEncoding);
            if (!a.isNative()) {
                throw InternalErrors.unsupportedOperation("string is not backed by a native pointer!");
            }
            return ((NativePointer) a.data()).getPointerObject();
        }

        /**
         * Create a new {@link GetInternalNativePointerNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static GetInternalNativePointerNode create() {
            return TruffleStringFactory.GetInternalNativePointerNodeGen.create();
        }

        /**
         * Get the uncached version of {@link GetInternalNativePointerNode}.
         *
         * @since 22.1
         */
        public static GetInternalNativePointerNode getUncached() {
            return TruffleStringFactory.GetInternalNativePointerNodeGen.getUncached();
        }
    }

    /**
     * Node to copy a region of a string into a byte array. See
     * {@link #execute(AbstractTruffleString, int, byte[], int, int, TruffleString.Encoding)} for
     * details.
     *
     * @since 22.1
     */
    public abstract static class CopyToByteArrayNode extends AbstractPublicNode {

        CopyToByteArrayNode() {
        }

        /**
         * Copy the entire string to a byte[] and return it.
         *
         * @since 22.2
         */
        public final byte[] execute(AbstractTruffleString string, Encoding expectedEncoding) {
            int byteLength = string.byteLength(expectedEncoding);
            byte[] copy = new byte[byteLength];
            execute(string, 0, copy, 0, byteLength, expectedEncoding);
            return copy;
        }

        /**
         * Copy a region of the given {@link TruffleString} {@code a}, bounded by
         * {@code byteFromIndexA} and {@code byteLength} into the given byte array, at starting at
         * {@code byteFromIndexDst}.
         *
         * @since 22.1
         */
        public abstract void execute(AbstractTruffleString a, int byteFromIndexA, byte[] dst, int byteFromIndexDst, int byteLength, Encoding expectedEncoding);

        @Specialization
        final void doCopy(AbstractTruffleString a, int byteFromIndexA, byte[] dst, int byteFromIndexDst, int byteLength, Encoding expectedEncoding,
                        @Cached InternalCopyToByteArrayNode internalNode) {
            internalNode.execute(this, a, byteFromIndexA, dst, byteFromIndexDst, byteLength, expectedEncoding);
        }

        /**
         * Create a new {@link CopyToByteArrayNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static CopyToByteArrayNode create() {
            return TruffleStringFactory.CopyToByteArrayNodeGen.create();
        }

        /**
         * Get the uncached version of {@link CopyToByteArrayNode}.
         *
         * @since 22.1
         */
        public static CopyToByteArrayNode getUncached() {
            return TruffleStringFactory.CopyToByteArrayNodeGen.getUncached();
        }
    }

    abstract static class InternalCopyToByteArrayNode extends AbstractInternalNode {

        abstract void execute(Node node, AbstractTruffleString a, int byteFromIndexA, byte[] dst, int byteFromIndexDst, int byteLength, Encoding expectedEncoding);

        @Specialization
        static void doCopy(Node node, AbstractTruffleString a, int byteFromIndexA, byte[] arrayB, int byteFromIndexB, int byteLength, Encoding expectedEncoding,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached InlinedConditionProfile utf16Profile,
                        @Cached InlinedConditionProfile utf16S0Profile,
                        @Cached InlinedConditionProfile utf32Profile,
                        @Cached InlinedConditionProfile utf32S0Profile,
                        @Cached InlinedConditionProfile utf32S1Profile) {
            boundsCheckRegionI(byteFromIndexB, byteLength, arrayB.length);
            doCopyInternal(node, a, byteFromIndexA, arrayB, byteArrayBaseOffset(), byteFromIndexB, byteLength, expectedEncoding,
                            managedProfileA, nativeProfileA, utf16Profile, utf16S0Profile, utf32Profile, utf32S0Profile, utf32S1Profile);
        }

        private static void doCopyInternal(Node node, AbstractTruffleString a, int byteFromIndexA, byte[] arrayB, long offsetB, int byteFromIndexB, int byteLength, Encoding expectedEncoding,
                        InlinedConditionProfile managedProfileA,
                        InlinedConditionProfile nativeProfileA,
                        InlinedConditionProfile utf16Profile,
                        InlinedConditionProfile utf16S0Profile,
                        InlinedConditionProfile utf32Profile,
                        InlinedConditionProfile utf32S0Profile,
                        InlinedConditionProfile utf32S1Profile) {
            if (byteLength == 0) {
                return;
            }
            a.checkEncoding(expectedEncoding);
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(node, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(node, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(node, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                if (utf16Profile.profile(node, isUTF16(expectedEncoding))) {
                    a.boundsCheckByteIndexUTF16(byteFromIndexA);
                    checkByteLengthUTF16(byteLength);
                    final int fromIndexA = rawIndex(byteFromIndexA, expectedEncoding);
                    final int fromIndexB = rawIndex(byteFromIndexB, expectedEncoding);
                    final int length = rawIndex(byteLength, expectedEncoding);
                    a.boundsCheckRegionRaw(fromIndexA, length);
                    if (utf16S0Profile.profile(node, isStride0(a))) {
                        TStringOps.arraycopyWithStride(node,
                                        arrayA, offsetA, 0, fromIndexA,
                                        arrayB, offsetB, 1, fromIndexB, length);
                        return;
                    }
                } else if (utf32Profile.profile(node, isUTF32(expectedEncoding))) {
                    a.boundsCheckByteIndexUTF32(byteFromIndexA);
                    checkByteLengthUTF32(byteLength);
                    final int fromIndexA = rawIndex(byteFromIndexA, expectedEncoding);
                    final int fromIndexB = rawIndex(byteFromIndexB, expectedEncoding);
                    final int length = rawIndex(byteLength, expectedEncoding);
                    a.boundsCheckRegionRaw(fromIndexA, length);
                    if (utf32S0Profile.profile(node, isStride0(a))) {
                        TStringOps.arraycopyWithStride(node,
                                        arrayA, offsetA, 0, fromIndexA,
                                        arrayB, offsetB, 2, fromIndexB, length);
                        return;
                    }
                    if (utf32S1Profile.profile(node, isStride1(a))) {
                        TStringOps.arraycopyWithStride(node,
                                        arrayA, offsetA, 1, fromIndexA,
                                        arrayB, offsetB, 2, fromIndexB, length);
                        return;
                    }
                }
                assert a.stride() == expectedEncoding.naturalStride;
                final int byteLengthA = a.length() << a.stride();
                boundsCheckRegionI(byteFromIndexA, byteLength, byteLengthA);
                TStringOps.arraycopyWithStride(node,
                                arrayA, offsetA, 0, byteFromIndexA,
                                arrayB, offsetB, 0, byteFromIndexB, byteLength);
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

    }

    /**
     * Node to copy a region of a string into native memory. See
     * {@link #execute(AbstractTruffleString, int, Object, int, int, TruffleString.Encoding)} for
     * details.
     *
     * @since 22.1
     */
    public abstract static class CopyToNativeMemoryNode extends AbstractPublicNode {

        CopyToNativeMemoryNode() {
        }

        /**
         * Copy a region of the given {@link TruffleString} {@code a}, bounded by
         * {@code byteFromIndexA} and {@code byteLength} into the given interop object representing
         * a native pointer ({@code isPointer(pointerObject)} must return {@code true}), starting at
         * {@code byteFromIndexDst}.
         * <p>
         * This operation requires native access permissions
         * ({@code TruffleLanguage.Env#isNativeAccessAllowed()}).
         *
         * @since 22.1
         */
        public abstract void execute(AbstractTruffleString a, int byteFromIndexA, Object pointerObject, int byteFromIndexDst, int byteLength, Encoding expectedEncoding);

        @Specialization
        void doCopy(AbstractTruffleString a, int byteFromIndexA, Object pointerObject, int byteFromIndexB, int byteLength, Encoding expectedEncoding,
                        @Cached(value = "createInteropLibrary()", uncached = "getUncachedInteropLibrary()") Node interopLibrary,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached InlinedConditionProfile utf16Profile,
                        @Cached InlinedConditionProfile utf16S0Profile,
                        @Cached InlinedConditionProfile utf32Profile,
                        @Cached InlinedConditionProfile utf32S0Profile,
                        @Cached InlinedConditionProfile utf32S1Profile) {
            NativePointer nativePointer = NativePointer.create(this, pointerObject, interopLibrary);
            InternalCopyToByteArrayNode.doCopyInternal(this, a, byteFromIndexA, null, nativePointer.pointer, byteFromIndexB,
                            byteLength,
                            expectedEncoding, managedProfileA, nativeProfileA, utf16Profile, utf16S0Profile, utf32Profile, utf32S0Profile, utf32S1Profile);
            Reference.reachabilityFence(pointerObject);
        }

        /**
         * Create a new {@link CopyToNativeMemoryNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static CopyToNativeMemoryNode create() {
            return TruffleStringFactory.CopyToNativeMemoryNodeGen.create();
        }

        /**
         * Get the uncached version of {@link CopyToNativeMemoryNode}.
         *
         * @since 22.1
         */
        public static CopyToNativeMemoryNode getUncached() {
            return TruffleStringFactory.CopyToNativeMemoryNodeGen.getUncached();
        }
    }

    /**
     * Node to get a {@link java.lang.String} representation of a {@link TruffleString}.
     *
     * @since 22.1
     */
    public abstract static class ToJavaStringNode extends AbstractPublicNode {

        ToJavaStringNode() {
        }

        /**
         * Return a {@link java.lang.String} representation of the given {@link TruffleString}.
         *
         * @since 22.1
         */
        public abstract String execute(AbstractTruffleString a);

        @Specialization
        static String doUTF16(TruffleString a,
                        @Bind Node node,
                        @Cached InlinedConditionProfile cacheHit,
                        @Cached @Exclusive InlinedConditionProfile managedProfileA,
                        @Cached @Exclusive InlinedConditionProfile nativeProfileA,
                        @Cached @Shared TStringInternalNodes.GetCodePointLengthNode getCodePointLengthNode,
                        @Cached @Shared TStringInternalNodes.GetPreciseCodeRangeWithMaterializationNode getPreciseCodeRangeNode,
                        @Cached @Shared TStringInternalNodes.TransCodeNode transCodeNode,
                        @Cached @Shared TStringInternalNodes.CreateJavaStringNode createJavaStringNode,
                        @Cached @Shared InlinedConditionProfile noTranscodeProfile) {
            if (a.isEmpty()) {
                return "";
            }
            TruffleString cur = a.next;
            if (cur != null) {
                while (cur != a && !cur.isJavaString()) {
                    cur = cur.next;
                }
                if (cacheHit.profile(node, cur.isJavaString())) {
                    return (String) cur.data();
                }
            }
            cur = a.next;
            if (cur != null) {
                while (cur != a && !cur.isCompatibleToIntl(Encoding.UTF_16)) {
                    cur = cur.next;
                }
            } else {
                cur = a;
            }
            if (cur.isJavaString()) {
                // java string was inserted in parallel
                return (String) cur.data();
            }
            Encoding encodingA = Encoding.get(cur.encoding());
            final AbstractTruffleString utf16String;
            final byte[] utf16Array;
            final long utf16Offset;

            Object dataCur = cur.data();
            try {
                final byte[] arrayCur;
                final long addOffsetCur;
                if (managedProfileA.profile(node, dataCur instanceof byte[])) {
                    arrayCur = (byte[]) dataCur;
                    addOffsetCur = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(node, dataCur instanceof NativePointer)) {
                    arrayCur = null;
                    addOffsetCur = NativePointer.unwrap(dataCur);
                } else {
                    arrayCur = cur.materializeLazy(node, dataCur);
                    addOffsetCur = byteArrayBaseOffset();
                }
                final long offsetCur = cur.offset() + addOffsetCur;

                if (noTranscodeProfile.profile(node, doesNotNeedTranscoding(node, cur, encodingA, getPreciseCodeRangeNode))) {
                    utf16String = cur;
                    utf16Array = arrayCur;
                    utf16Offset = offsetCur;
                } else {
                    assert TSCodeRange.isPrecise(cur.codeRange());
                    TruffleString transCoded = transCodeNode.execute(node, cur, arrayCur, offsetCur, getCodePointLengthNode.execute(node, cur, arrayCur, offsetCur, encodingA), cur.codeRange(),
                                    Encoding.UTF_16, TranscodingErrorHandler.DEFAULT);
                    if (!transCoded.isCacheHead()) {
                        a.cacheInsert(transCoded);
                    }
                    utf16String = transCoded;
                    utf16Array = (byte[]) transCoded.data();
                    assert transCoded.isManaged();
                    utf16Offset = byteArrayBaseOffset() + transCoded.offset();
                }
                String javaString = createJavaStringNode.execute(node, utf16String, utf16Array, utf16Offset);
                a.cacheInsert(TruffleString.createWrapJavaString(javaString, utf16String.codePointLength(), utf16String.codeRange()));
                return javaString;
            } finally {
                Reference.reachabilityFence(dataCur);
            }
        }

        @Specialization
        static String doMutable(MutableTruffleString a,
                        @Bind Node node,
                        @Cached @Exclusive InlinedConditionProfile managedProfileA,
                        @Cached @Shared TStringInternalNodes.GetCodePointLengthNode getCodePointLengthNode,
                        @Cached @Shared TStringInternalNodes.GetPreciseCodeRangeWithMaterializationNode getPreciseCodeRangeNode,
                        @Cached @Shared TStringInternalNodes.TransCodeNode transCodeNode,
                        @Cached @Shared TStringInternalNodes.CreateJavaStringNode createJavaStringNode,
                        @Cached @Shared InlinedConditionProfile noTranscodeProfile) {
            if (a.isEmpty()) {
                return "";
            }
            AbstractTruffleString utf16String;
            Encoding encodingA = Encoding.get(a.encoding());
            final byte[] utf16Array;
            final long utf16Offset;

            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(node, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                }
                final long offsetA = a.offset() + addOffsetA;

                if (noTranscodeProfile.profile(node, doesNotNeedTranscoding(node, a, encodingA, getPreciseCodeRangeNode))) {
                    utf16String = a;
                    utf16Array = arrayA;
                    utf16Offset = offsetA;
                } else {
                    assert TSCodeRange.isPrecise(a.codeRange());
                    utf16String = transCodeNode.execute(node, a, arrayA, offsetA, getCodePointLengthNode.execute(node, a, arrayA, offsetA, encodingA), a.codeRange(), Encoding.UTF_16,
                                    TranscodingErrorHandler.DEFAULT);
                    utf16Array = (byte[]) utf16String.data();
                    utf16Offset = byteArrayBaseOffset();
                }
                return createJavaStringNode.execute(node, utf16String, utf16Array, utf16Offset);
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        private static boolean doesNotNeedTranscoding(Node node, AbstractTruffleString a, Encoding encodingA, TStringInternalNodes.GetPreciseCodeRangeWithMaterializationNode getPreciseCodeRangeNode) {
            // The order in this check is important for string compaction: First check if the
            // current, possibly imprecise code range is already in compaction range. Otherwise, we
            // _must_ calculate the precise code range, to make sure the string is compacted if
            // possible.
            return is7Or8Bit(a.codeRange()) || TSCodeRange.isMoreRestrictiveThan(getPreciseCodeRangeNode.execute(node, a, encodingA), Encoding.UTF_16.maxCompatibleCodeRange) ||
                            isUTF16(a.encoding());
        }

        /**
         * Create a new {@link ToJavaStringNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static ToJavaStringNode create() {
            return TruffleStringFactory.ToJavaStringNodeGen.create();
        }

        /**
         * Get the uncached version of {@link ToJavaStringNode}.
         *
         * @since 22.1
         */
        public static ToJavaStringNode getUncached() {
            return TruffleStringFactory.ToJavaStringNodeGen.getUncached();
        }
    }

    /**
     * Node to convert a potentially {@link #isManaged() managed} {@link TruffleString} to a
     * {@link #isNative() native} string.
     *
     * @since 23.0
     */
    public abstract static class AsNativeNode extends AbstractPublicNode {

        private static final int NULL_TERMINATION_BYTES = 4;

        AsNativeNode() {
        }

        /**
         * Convert a potentially {@link #isManaged() managed} {@link TruffleString} to a
         * {@link #isNative() native} string. If the given string is {@link #isNative() native}
         * already, it is returned. Otherwise, a new string with a backing native buffer allocated
         * via {@code allocator} is created and stored in the given managed string's internal
         * transcoding cache, such that subsequent calls on the same string will return the same
         * native string. This operation requires native access permissions
         * ({@code TruffleLanguage.Env#isNativeAccessAllowed()}).
         *
         * @param allocator a function implementing {@link NativeAllocator}. This parameter is
         *            expected to be {@link CompilerAsserts#partialEvaluationConstant(Object)
         *            partial evaluation constant}.
         * @param useCompaction if set to {@code true}, {@link Encoding#UTF_32} and
         *            {@link Encoding#UTF_16} - encoded strings may be compacted also in the native
         *            representation. Otherwise, no string compaction is applied to the native
         *            string. This parameter is expected to be
         *            {@link CompilerAsserts#partialEvaluationConstant(Object) partial evaluation
         *            constant}.
         * @param cacheResult if set to {@code true}, the newly created native string will be cached
         *            in the given managed string's internal transcoding cache ring, guaranteeing
         *            that subsequent calls on the managed string return the same native string.
         *            Note that this ties the lifetime of the native string to that of the managed
         *            string. This parameter is expected to be
         *            {@link CompilerAsserts#partialEvaluationConstant(Object) partial evaluation
         *            constant}.
         *
         * @since 23.0
         */
        public abstract TruffleString execute(TruffleString a, NativeAllocator allocator, Encoding expectedEncoding, boolean useCompaction, boolean cacheResult);

        @Specialization
        static TruffleString asNative(TruffleString a, NativeAllocator allocator, Encoding encoding, boolean useCompaction, boolean cacheResult,
                        @Bind Node node,
                        @Cached(value = "createInteropLibrary()", uncached = "getUncachedInteropLibrary()") Node interopLibrary,
                        @Cached InlinedConditionProfile isNativeProfile,
                        @Cached InlinedConditionProfile cacheHit,
                        @Cached InlinedIntValueProfile inflateStrideProfile,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.GetPreciseCodeRangeNode getPreciseCodeRangeNode) {
            a.checkEncoding(encoding);
            CompilerAsserts.partialEvaluationConstant(allocator);
            CompilerAsserts.partialEvaluationConstant(useCompaction);
            CompilerAsserts.partialEvaluationConstant(cacheResult);
            int strideA = inflateStrideProfile.profile(node, a.stride());

            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(node, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(node, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(node, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                int codeRangeA = getPreciseCodeRangeNode.execute(node, a, arrayA, offsetA, encoding);
                if (isNativeProfile.profile(node, a.isNative() && strideA == (useCompaction ? Stride.fromCodeRange(codeRangeA, encoding) : encoding.naturalStride))) {
                    return a;
                }
                TruffleString cur = a.next;
                assert !a.isJavaString();
                if (cacheResult && cur != null) {
                    while (cur != a && (!cur.isNative() || !cur.isCompatibleToIntl(encoding) || cur.stride() != (useCompaction ? strideA : encoding.naturalStride))) {
                        cur = cur.next;
                    }
                    if (cacheHit.profile(node, cur != a)) {
                        assert cur.isCompatibleToIntl(encoding) && cur.isNative() && !cur.isJavaString() && cur.stride() == (useCompaction ? strideA : encoding.naturalStride);
                        return cur;
                    }
                }
                int length = a.length();
                int stride = useCompaction ? Stride.fromCodeRange(codeRangeA, encoding) : encoding.naturalStride;
                int byteSize = length << stride;
                Object buffer = allocator.allocate(byteSize + NULL_TERMINATION_BYTES);
                NativePointer nativePointer = NativePointer.create(node, buffer, interopLibrary);
                try {
                    if (useCompaction) {
                        TStringOps.arraycopyWithStride(node, arrayA, offsetA, strideA, 0, null, nativePointer.pointer, stride, 0, length);
                    } else {
                        if (isUTF16(encoding)) {
                            TStringOps.arraycopyWithStride(node, arrayA, offsetA, strideA, 0, null, nativePointer.pointer, 1, 0, length);
                        } else if (isUTF32(encoding)) {
                            TStringOps.arraycopyWithStride(node, arrayA, offsetA, strideA, 0, null, nativePointer.pointer, 2, 0, length);
                        } else {
                            TStringOps.arraycopyWithStride(node, arrayA, offsetA, 0, 0, null, nativePointer.pointer, 0, 0, byteSize);
                        }
                    }
                    // Zero-terminate the string with four zero bytes, to make absolutely sure any
                    // native code expecting zero-terminated strings can deal with the buffer.
                    // This is to avoid potential problems with UTF-32 encoded strings, where native
                    // code
                    // may not read single bytes but 32-bit values.
                    checkIntSize();
                    TStringUnsafe.putInt(null, nativePointer.pointer + byteSize, 0);
                } finally {
                    // probably not necessary because the native object is referenced by the return
                    // value, but better safe than sorry
                    Reference.reachabilityFence(nativePointer);
                }
                TruffleString nativeString = TruffleString.createFromArray(nativePointer, 0, length, stride, encoding, a.codePointLength(), codeRangeA, !cacheResult);
                if (cacheResult) {
                    a.cacheInsert(nativeString);
                }
                return nativeString;
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        @SuppressWarnings("all")
        private static void checkIntSize() {
            assert Integer.BYTES == NULL_TERMINATION_BYTES;
        }

        /**
         * Create a new {@link AsNativeNode}.
         *
         * @since 23.0
         */
        @NeverDefault
        public static AsNativeNode create() {
            return TruffleStringFactory.AsNativeNodeGen.create();
        }

        /**
         * Get the uncached version of {@link AsNativeNode}.
         *
         * @since 23.0
         */
        public static AsNativeNode getUncached() {
            return TruffleStringFactory.AsNativeNodeGen.getUncached();
        }
    }

    /**
     * Shorthand for calling the uncached version of {@link AsNativeNode}.
     *
     * @since 23.0
     */
    public TruffleString asNativeUncached(NativeAllocator allocator, Encoding expectedEncoding, boolean useCompaction, boolean cacheResult) {
        return AsNativeNode.getUncached().execute(this, allocator, expectedEncoding, useCompaction, cacheResult);
    }

    /**
     * Node to replace all invalid bytes in a given string, such that the resulting string is
     * {@link IsValidNode valid}. See
     * {@link #execute(AbstractTruffleString, TruffleString.Encoding)} for details.
     *
     * @since 23.1
     */
    public abstract static class ToValidStringNode extends AbstractPublicNode {

        ToValidStringNode() {
        }

        /**
         * Returns a version of string {@code a} that contains only valid codepoints, which may be
         * the string itself or a converted version. Invalid byte sequences are replaced with
         * {@code '\ufffd'} (for UTF-*) or {@code '?'}. This is useful for string sanitization in
         * all uses cases where a string is required to actually be {@link IsValidNode valid}, such
         * as libraries that actively reject broken input, network and file system I/O, etc.
         *
         * @since 23.1
         */
        public abstract TruffleString execute(AbstractTruffleString a, Encoding expectedEncoding);

        @Specialization
        final TruffleString toValid(AbstractTruffleString a, Encoding encoding,
                        @Cached InlinedConditionProfile isValidProfile,
                        @Cached TStringInternalNodes.GetValidOrBrokenCodeRangeNode getCodeRangeNode,
                        @Cached InternalAsTruffleStringNode asTruffleStringNode,
                        @Cached TStringInternalNodes.ToValidStringNode internalNode,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA) {
            a.checkEncoding(encoding);
            int codeRangeA = getCodeRangeNode.execute(this, a, encoding);
            if (isValidProfile.profile(this, !isBroken(codeRangeA))) {
                return asTruffleStringNode.execute(this, a, encoding);
            }
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                return internalNode.execute(this, a, arrayA, offsetA, encoding);
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link SwitchEncodingNode}.
         *
         * @since 23.1
         */
        @NeverDefault
        public static ToValidStringNode create() {
            return TruffleStringFactory.ToValidStringNodeGen.create();
        }

        /**
         * Get the uncached version of {@link SwitchEncodingNode}.
         *
         * @since 23.1
         */
        public static ToValidStringNode getUncached() {
            return TruffleStringFactory.ToValidStringNodeGen.getUncached();
        }
    }

    /**
     * Node to get a given string in a specific encoding. See
     * {@link #execute(AbstractTruffleString, TruffleString.Encoding)} for details.
     *
     * @since 22.1
     */
    public abstract static class SwitchEncodingNode extends AbstractPublicNode {

        SwitchEncodingNode() {
        }

        /**
         * Returns a version of string {@code a} that is encoded in the given encoding, which may be
         * the string itself or a converted version. Note that the string itself may be returned
         * even if it was originally created using a different encoding, if the string is
         * byte-equivalent in both encodings.
         * <p>
         * If no lossless conversion is possible, the string is converted on a best-effort basis; no
         * exception is thrown and characters which cannot be mapped in the target encoding are
         * replaced by {@code '\ufffd'} (for UTF-*) or {@code '?'}.
         *
         * @since 22.1
         */
        public final TruffleString execute(AbstractTruffleString a, Encoding encoding) {
            return execute(a, encoding, TranscodingErrorHandler.DEFAULT);
        }

        /**
         * Returns a version of string {@code a} that is encoded in the given encoding, which may be
         * the string itself or a converted version. Note that the string itself may be returned
         * even if it was originally created using a different encoding, if the string is
         * byte-equivalent in both encodings. Transcoding errors are handled with
         * {@code errorHandler}.
         *
         * @since 23.1
         */
        public abstract TruffleString execute(AbstractTruffleString a, Encoding encoding, TranscodingErrorHandler errorHandler);

        @Specialization
        final TruffleString switchEncoding(AbstractTruffleString a, Encoding encoding, TranscodingErrorHandler errorHandler,
                        @Cached InternalSwitchEncodingNode internalNode) {
            return internalNode.execute(this, a, encoding, errorHandler);
        }

        /**
         * Create a new {@link SwitchEncodingNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static SwitchEncodingNode create() {
            return TruffleStringFactory.SwitchEncodingNodeGen.create();
        }

        /**
         * Get the uncached version of {@link SwitchEncodingNode}.
         *
         * @since 22.1
         */
        public static SwitchEncodingNode getUncached() {
            return TruffleStringFactory.SwitchEncodingNodeGen.getUncached();
        }
    }

    abstract static class InternalSwitchEncodingNode extends AbstractInternalNode {

        abstract TruffleString execute(Node node, AbstractTruffleString a, Encoding targetEncoding, TranscodingErrorHandler errorHandler);

        @Specialization
        static TruffleString immutable(Node node, TruffleString a, Encoding targetEncoding, TranscodingErrorHandler errorHandler,
                        @Cached TStringInternalNodes.GetPreciseCodeRangeWithMaterializationNode getPreciseCodeRangeNode,
                        @Cached @Exclusive InlinedConditionProfile isCompatibleProfile,
                        @Cached @Exclusive InlinedConditionProfile noOpProfile,
                        @Cached @Exclusive InlinedConditionProfile mustCompactProfile,
                        @Cached @Exclusive InlinedConditionProfile compact10Profile,
                        @Cached @Exclusive InlinedConditionProfile compact20Profile,
                        @Cached @Exclusive InlinedConditionProfile cacheHit,
                        @Cached @Exclusive InlinedConditionProfile managedProfileA,
                        @Cached @Exclusive InlinedConditionProfile nativeProfileA,
                        @Cached @Shared TStringInternalNodes.TransCodeNode transCodeNode) {
            final boolean isCompatible;
            final int preciseCodeRangeA;
            if (isCompatibleProfile.profile(node, a.isCompatibleToIntl(targetEncoding))) {
                isCompatible = true;
                preciseCodeRangeA = a.codeRange();
            } else {
                Encoding encodingA = Encoding.get(a.encoding());
                preciseCodeRangeA = getPreciseCodeRangeNode.execute(node, a, encodingA);
                isCompatible = a.isCodeRangeCompatibleTo(preciseCodeRangeA, targetEncoding);
            }
            final boolean mustCompact = a.stride() > targetEncoding.naturalStride;
            if (noOpProfile.profile(node, isCompatible && !mustCompact)) {
                assert !a.isJavaString();
                return a;
            }
            if (a.isEmpty()) {
                return targetEncoding.getEmpty();
            }
            TruffleString cur = a.next;
            assert !a.isJavaString();
            if (cur != null) {
                while (cur != a && cur.encoding() != targetEncoding.id || (isUTF16(targetEncoding) && cur.isJavaString())) {
                    cur = cur.next;
                }
                if (cacheHit.profile(node, cur.encoding() == targetEncoding.id)) {
                    assert !cur.isJavaString();
                    return cur;
                }
            }
            final TruffleString transCoded;
            if (mustCompactProfile.profile(node, isCompatible && mustCompact)) {
                Object dataA = a.data();
                assert dataA instanceof NativePointer;
                try {
                    final long offsetA = a.offset() + NativePointer.unwrap(dataA);
                    final int strideA = a.stride();
                    final int lengthA = a.length();
                    final int stride = Stride.fromCodeRangeUTF16(preciseCodeRangeA);
                    final byte[] array = new byte[lengthA << stride];
                    if (compact10Profile.profile(node, strideA == 1 && stride == 0)) {
                        TStringOps.arraycopyWithStride(node, null, offsetA, 1, 0, array, byteArrayBaseOffset(), 0, 0, lengthA);
                    } else if (compact20Profile.profile(node, strideA == 2 && stride == 0)) {
                        TStringOps.arraycopyWithStride(node, null, offsetA, 2, 0, array, byteArrayBaseOffset(), 0, 0, lengthA);
                    } else {
                        assert strideA == 2 && stride == 1;
                        TStringOps.arraycopyWithStride(node, null, offsetA, 2, 0, array, byteArrayBaseOffset(), 1, 0, lengthA);
                    }
                    transCoded = TruffleString.createFromByteArray(array, 0, lengthA, stride, targetEncoding, a.codePointLength(), preciseCodeRangeA, false);
                } finally {
                    Reference.reachabilityFence(dataA);
                }
            } else {
                Object dataA = a.data();
                try {
                    final byte[] arrayA;
                    final long addOffsetA;
                    if (managedProfileA.profile(node, dataA instanceof byte[])) {
                        arrayA = (byte[]) dataA;
                        addOffsetA = byteArrayBaseOffset();
                    } else if (nativeProfileA.profile(node, dataA instanceof NativePointer)) {
                        arrayA = null;
                        addOffsetA = NativePointer.unwrap(dataA);
                    } else {
                        arrayA = a.materializeLazy(node, dataA);
                        addOffsetA = byteArrayBaseOffset();
                    }
                    final long offsetA = a.offset() + addOffsetA;
                    transCoded = transCodeNode.execute(node, a, arrayA, offsetA, a.codePointLength(), preciseCodeRangeA, targetEncoding, errorHandler);
                } finally {
                    Reference.reachabilityFence(dataA);
                }
            }
            if (!transCoded.isCacheHead()) {
                a.cacheInsert(transCoded);
            }
            return transCoded;
        }

        @Specialization(guards = "a.isCompatibleToIntl(targetEncoding)")
        static TruffleString compatibleMutable(Node node, MutableTruffleString a, Encoding targetEncoding, @SuppressWarnings("unused") TranscodingErrorHandler errorHandler,
                        @Cached InternalAsTruffleStringNode asTruffleStringNode) {
            return asTruffleStringNode.execute(node, a, targetEncoding);
        }

        @Specialization(guards = "!a.isCompatibleToIntl(targetEncoding)")
        static TruffleString transCodeMutable(Node node, MutableTruffleString a, Encoding targetEncoding, TranscodingErrorHandler errorHandler,
                        @Cached @Exclusive InlinedConditionProfile managedProfileA,
                        @Cached TStringInternalNodes.GetCodePointLengthNode getCodePointLengthNode,
                        @Cached TStringInternalNodes.GetPreciseCodeRangeNode getPreciseCodeRangeNode,
                        @Cached @Shared TStringInternalNodes.TransCodeNode transCodeNode,
                        @Cached @Exclusive InlinedConditionProfile isCompatibleProfile) {
            if (a.isEmpty()) {
                return targetEncoding.getEmpty();
            }
            Encoding encodingA = Encoding.get(a.encoding());
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(node, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                }
                final long offsetA = a.offset() + addOffsetA;

                final int codePointLengthA = getCodePointLengthNode.execute(node, a, arrayA, offsetA, encodingA);
                final int codeRangeA = getPreciseCodeRangeNode.execute(node, a, arrayA, offsetA, encodingA);
                if (isCompatibleProfile.profile(node, TSCodeRange.isMoreRestrictiveThan(codeRangeA, targetEncoding.maxCompatibleCodeRange))) {
                    int strideDst = Stride.fromCodeRange(codeRangeA, targetEncoding);
                    byte[] arrayDst = new byte[a.length() << strideDst];
                    TStringOps.arraycopyWithStride(node,
                                    arrayA, offsetA, a.stride(), 0,
                                    arrayDst, byteArrayBaseOffset(), strideDst, 0, a.length());
                    return createFromByteArray(arrayDst, a.length(), strideDst, targetEncoding, codePointLengthA, codeRangeA);
                } else {
                    return transCodeNode.execute(node, a, arrayA, offsetA, codePointLengthA, codeRangeA, targetEncoding, errorHandler);
                }
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }
    }

    /**
     * Node to forcibly assign any encoding to a string. See
     * {@link #execute(AbstractTruffleString, TruffleString.Encoding, TruffleString.Encoding)} for
     * details.
     *
     * @since 22.1
     */
    public abstract static class ForceEncodingNode extends AbstractPublicNode {

        ForceEncodingNode() {
        }

        /**
         * Returns a version of string {@code a} assigned to the given encoding, which may be the
         * string itself or a new string. The string itself may be returned even if it was
         * originally created using a different encoding, if the string is byte-equivalent in both
         * encodings. If the string is not byte-equivalent in both encodings, a new string
         * containing the same bytes but assigned to the new encoding is returned. <b>This node does
         * not transcode the string's contents in any way, it is the "encoding-equivalent" to a
         * C-style reinterpret-cast.</b>
         *
         * @since 22.1
         */
        public abstract TruffleString execute(AbstractTruffleString a, Encoding expectedEncoding, Encoding targetEncoding);

        @Specialization(guards = "isCompatibleAndNotCompacted(a, expectedEncoding, targetEncoding)")
        static TruffleString compatibleImmutable(TruffleString a, @SuppressWarnings("unused") Encoding expectedEncoding, @SuppressWarnings("unused") Encoding targetEncoding) {
            assert !a.isJavaString();
            return a;
        }

        @Specialization(guards = "isCompatibleAndNotCompacted(a, expectedEncoding, targetEncoding)")
        final TruffleString compatibleMutable(MutableTruffleString a, @SuppressWarnings("unused") Encoding expectedEncoding, Encoding targetEncoding,
                        @Cached InternalAsTruffleStringNode asTruffleStringNode) {
            return asTruffleStringNode.execute(this, a, targetEncoding);
        }

        @Specialization(guards = "!isCompatibleAndNotCompacted(a, expectedEncoding, targetEncoding)")
        final TruffleString reinterpret(AbstractTruffleString a, Encoding expectedEncoding, Encoding targetEncoding,
                        @Cached ToIndexableNode toIndexableNode,
                        @Cached InlinedConditionProfile inflateProfile,
                        @Cached TruffleString.InternalCopyToByteArrayNode copyToByteArrayNode,
                        @Cached TStringInternalNodes.FromBufferWithStringCompactionNode fromBufferWithStringCompactionNode) {
            Object dataA = toIndexableNode.execute(this, a, a.data());
            int byteLength = a.length() << expectedEncoding.naturalStride;
            final Object dataANoCompaction;
            final int offset;
            if (inflateProfile.profile(this, isUTF16Or32(expectedEncoding) && a.stride() != expectedEncoding.naturalStride)) {
                byte[] inflated = new byte[byteLength];
                copyToByteArrayNode.execute(this, a, 0, inflated, 0, byteLength, expectedEncoding);
                dataANoCompaction = inflated;
                offset = 0;
            } else {
                dataANoCompaction = dataA;
                offset = a.offset();
            }
            return fromBufferWithStringCompactionNode.execute(this, dataANoCompaction, offset, byteLength, targetEncoding, a.isMutable(), true);
        }

        static boolean isCompatibleAndNotCompacted(AbstractTruffleString a, Encoding expectedEncoding, Encoding targetEncoding) {
            return expectedEncoding.naturalStride == targetEncoding.naturalStride &&
                            (a.encoding() == targetEncoding.id || a.stride() == targetEncoding.naturalStride && a.isCompatibleToIntl(targetEncoding));
        }

        /**
         * Create a new {@link ForceEncodingNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static ForceEncodingNode create() {
            return TruffleStringFactory.ForceEncodingNodeGen.create();
        }

        /**
         * Get the uncached version of {@link ForceEncodingNode}.
         *
         * @since 22.1
         */
        public static ForceEncodingNode getUncached() {
            return TruffleStringFactory.ForceEncodingNodeGen.getUncached();
        }
    }

    /**
     * Node to create a {@link TruffleStringIterator}. See
     * {@link #execute(AbstractTruffleString, TruffleString.Encoding)} for details.
     *
     * @since 22.1
     */
    public abstract static class CreateCodePointIteratorNode extends AbstractPublicNode {

        CreateCodePointIteratorNode() {
        }

        /**
         * Returns a {@link TruffleStringIterator}, which allows iterating this string's code
         * points, with {@link ErrorHandling#BEST_EFFORT best-effort error handling}.
         *
         * @since 22.1
         */
        public final TruffleStringIterator execute(AbstractTruffleString a, Encoding expectedEncoding) {
            return execute(a, expectedEncoding, ErrorHandling.BEST_EFFORT);
        }

        /**
         * Returns a {@link TruffleStringIterator}, which allows iterating this string's code
         * points. The iterator is initialized to begin iteration at the start of the string, use
         * {@link TruffleStringIterator.NextNode} to iterate.
         *
         * @param errorHandling analogous to {@link CodePointAtIndexNode}.
         *
         * @since 22.3
         */
        public abstract TruffleStringIterator execute(AbstractTruffleString a, Encoding expectedEncoding, ErrorHandling errorHandling);

        @Specialization
        final TruffleStringIterator createIterator(AbstractTruffleString a, Encoding encoding, ErrorHandling errorHandling,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeANode) {
            CompilerAsserts.partialEvaluationConstant(errorHandling);
            a.checkEncoding(encoding);
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                int codeRangeA = getCodeRangeANode.execute(this, a, arrayA, offsetA, encoding);
                return forwardIterator(a, arrayA, offsetA, codeRangeA, encoding, errorHandling);
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link CreateCodePointIteratorNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static CreateCodePointIteratorNode create() {
            return TruffleStringFactory.CreateCodePointIteratorNodeGen.create();
        }

        /**
         * Get the uncached version of {@link CreateCodePointIteratorNode}.
         *
         * @since 22.1
         */
        public static CreateCodePointIteratorNode getUncached() {
            return TruffleStringFactory.CreateCodePointIteratorNodeGen.getUncached();
        }
    }

    /**
     * Node to create a {@link TruffleStringIterator}. See
     * {@link #execute(AbstractTruffleString, TruffleString.Encoding)} for details.
     *
     * @since 22.1
     */
    public abstract static class CreateBackwardCodePointIteratorNode extends AbstractPublicNode {

        CreateBackwardCodePointIteratorNode() {
        }

        /**
         * Returns a {@link TruffleStringIterator}, which allows iterating this string's code
         * points, with {@link ErrorHandling#BEST_EFFORT best-effort error handling}.
         *
         * @since 22.1
         */
        public final TruffleStringIterator execute(AbstractTruffleString a, Encoding expectedEncoding) {
            return execute(a, expectedEncoding, ErrorHandling.BEST_EFFORT);
        }

        /**
         * Returns a {@link TruffleStringIterator}, which allows iterating this string's code
         * points. The iterator is initialized to begin iteration at the end of the string, use
         * {@link TruffleStringIterator.PreviousNode} to iterate in reverse order.
         *
         * @param errorHandling analogous to {@link CodePointAtIndexNode}.
         *
         * @since 22.3
         */
        public abstract TruffleStringIterator execute(AbstractTruffleString a, Encoding expectedEncoding, ErrorHandling errorHandling);

        @Specialization
        final TruffleStringIterator createIterator(AbstractTruffleString a, Encoding encoding, ErrorHandling errorHandling,
                        @Cached InlinedConditionProfile managedProfileA,
                        @Cached InlinedConditionProfile nativeProfileA,
                        @Cached TStringInternalNodes.GetCodeRangeForIndexCalculationNode getCodeRangeANode) {
            CompilerAsserts.partialEvaluationConstant(errorHandling);
            a.checkEncoding(encoding);
            Object dataA = a.data();
            try {
                final byte[] arrayA;
                final long addOffsetA;
                if (managedProfileA.profile(this, dataA instanceof byte[])) {
                    arrayA = (byte[]) dataA;
                    addOffsetA = byteArrayBaseOffset();
                } else if (nativeProfileA.profile(this, dataA instanceof NativePointer)) {
                    arrayA = null;
                    addOffsetA = NativePointer.unwrap(dataA);
                } else {
                    arrayA = a.materializeLazy(this, dataA);
                    addOffsetA = byteArrayBaseOffset();
                }
                final long offsetA = a.offset() + addOffsetA;

                int codeRangeA = getCodeRangeANode.execute(this, a, arrayA, offsetA, encoding);
                return backwardIterator(a, arrayA, offsetA, codeRangeA, encoding, errorHandling);
            } finally {
                Reference.reachabilityFence(dataA);
            }
        }

        /**
         * Create a new {@link CreateBackwardCodePointIteratorNode}.
         *
         * @since 22.1
         */
        @NeverDefault
        public static CreateBackwardCodePointIteratorNode create() {
            return TruffleStringFactory.CreateBackwardCodePointIteratorNodeGen.create();
        }

        /**
         * Get the uncached version of {@link CreateBackwardCodePointIteratorNode}.
         *
         * @since 22.1
         */
        public static CreateBackwardCodePointIteratorNode getUncached() {
            return TruffleStringFactory.CreateBackwardCodePointIteratorNodeGen.getUncached();
        }
    }
}
