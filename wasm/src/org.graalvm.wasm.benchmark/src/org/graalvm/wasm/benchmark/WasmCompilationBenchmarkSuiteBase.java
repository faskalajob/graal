/*
 * Copyright (c) 2019, 2023, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.wasm.benchmark;

import org.graalvm.polyglot.Context;
import org.graalvm.wasm.WasmLanguage;
import org.graalvm.wasm.utils.WasmBinaryTools;
import org.graalvm.wasm.utils.cases.WasmCase;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.util.EnumSet;

/**
 * This benchmark base class runs a workload that compiles the given test case (but does not run
 * it). This is done by calling `Context.evaluate` on each source.
 */
@Warmup(iterations = 2)
@Measurement(iterations = 6)
@Fork(1)
@State(Scope.Benchmark)
public abstract class WasmCompilationBenchmarkSuiteBase {
    public abstract static class WasmCompilationBenchmarkState {
        private Context context;
        private WasmCase benchmarkCase;

        abstract protected String benchmarkResource();

        @Setup(Level.Trial)
        public void setup() throws IOException {
            benchmarkCase = WasmCase.loadBenchmarkCase(this.getClass(), benchmarkResource());
        }

        @Setup(Level.Invocation)
        public void setupInvocation() {
            final Context.Builder contextBuilder = Context.newBuilder(WasmLanguage.ID);
            contextBuilder.option("wasm.Builtins", "testutil,env:emscripten,wasi_snapshot_preview1");
            context = contextBuilder.build();
        }

        @TearDown(Level.Invocation)
        public void teardownInvocation() {
            context.close();
            context = null;
        }

        public void run() throws IOException, InterruptedException {
            benchmarkCase.getSources(EnumSet.noneOf(WasmBinaryTools.WabtOption.class)).forEach(context::eval);
        }
    }
}
