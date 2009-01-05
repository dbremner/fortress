/*******************************************************************************
    Copyright 2009 Sun Microsystems, Inc.,
    4150 Network Circle, Santa Clara, California 95054, U.S.A.
    All rights reserved.

    U.S. Government Rights - Commercial software.
    Government users are subject to the Sun Microsystems, Inc. standard
    license agreement and applicable provisions of the FAR and its supplements.

    Use is subject to license terms.

    This distribution may include materials developed by third parties.

    Sun, Sun Microsystems, the Sun logo and Java are trademarks or registered
    trademarks of Sun Microsystems, Inc. in the U.S. and other countries.
 ******************************************************************************/

package com.sun.fortress.interpreter.glue.prim;

import java.util.List;

import com.sun.fortress.interpreter.evaluator.Environment;
import com.sun.fortress.interpreter.evaluator.types.FTypeObject;
import com.sun.fortress.interpreter.evaluator.values.FBool;
import com.sun.fortress.interpreter.evaluator.values.FChar;
import com.sun.fortress.interpreter.evaluator.values.FInt;
import com.sun.fortress.interpreter.evaluator.values.FObject;
import com.sun.fortress.interpreter.evaluator.values.FString;
import com.sun.fortress.interpreter.evaluator.values.FValue;
import com.sun.fortress.interpreter.evaluator.values.NativeConstructor;
import com.sun.fortress.interpreter.glue.NativeMeth0;
import com.sun.fortress.interpreter.glue.NativeMeth1;
import com.sun.fortress.interpreter.glue.NativeMeth2;
import com.sun.fortress.nodes.ObjectConstructor;


public class FlatString extends NativeConstructor {

    public FlatString(Environment env,
                  FTypeObject selfType,
                  ObjectConstructor def) {
        super(env,selfType,def);
    }

    @Override
    protected FNativeObject makeNativeObject(List<FValue> args,
                                             NativeConstructor con) {
        FString.setConstructor(this);
        return FString.EMPTY;
    }

    @Override
    protected void unregister() {
        FString.resetConstructor();
    }

    private static abstract class ss2S extends NativeMeth1 {
        protected abstract java.lang.String f(java.lang.String s, java.lang.String o);
        @Override
        public final FString applyMethod(FObject self, FValue other) {
            return FString.make(f(self.getString(),other.getString()));
        }
    }

    private static abstract class ss2B extends NativeMeth1 {
        protected abstract boolean f(java.lang.String s, java.lang.String o);
        @Override
        public final FBool applyMethod(FObject self, FValue other) {
            return FBool.make(f(((FString)self).getString(),
                                ((FString)other).getString()));
        }
    }

    private static abstract class ss2I extends NativeMeth1 {
        protected abstract int f(java.lang.String s, java.lang.String o);
        @Override
        public final FInt applyMethod(FObject self, FValue other) {
            return FInt.make(f(((FString)self).getString(),
                               ((FString)other).getString()));
        }
    }

    private static abstract class s2I extends NativeMeth0 {
        protected abstract int f(java.lang.String s);
        @Override
        public final FInt applyMethod(FObject self) {
            return FInt.make(f(((FString)self).getString()));
        }
    }

    private static abstract class sII2s extends NativeMeth2 {
        protected abstract java.lang.String f(java.lang.String s, int lo, int hi);
        @Override
        public final FString applyMethod(FObject self, FValue lo, FValue hi) {
            return FString.make(f(((FString)self).getString(),
                                  ((FInt)lo).getInt(),
                                  ((FInt)hi).getInt()));
        }
    }

    protected static abstract class s2s extends NativeMeth0 {
        protected abstract java.lang.String f(FString s);
        @Override
        public final FString applyMethod(FObject self) {
            return FString.make(f((FString) self));
        }
    }

    protected static abstract class sI2C extends NativeMeth1 {
        protected abstract char f(java.lang.String s, int i);
        @Override
        public final FChar applyMethod(FObject self, FValue i) {
            return FChar.make(f(((FString)self).getString(),
                                ((FInt)i).getInt()));
        }
    }

    protected static abstract class sC2I extends NativeMeth1 {
        protected abstract int f(java.lang.String s, int c);
        @Override
        public final FInt applyMethod(FObject self, FValue c) {
            return FInt.make(f(((FString)self).getString(),
                               ((FChar)c).getChar()));
        }
    }

    public static final class Size extends s2I {
        @Override
        protected int f(java.lang.String s) {
            return s.length();
        }
    }

    public static final class Eq extends ss2B {
        @Override
        protected boolean f(java.lang.String self, java.lang.String other) {
            return self.equals(other);
        }
    }

    public static final class Cmp extends ss2I {
        @Override
        protected int f(java.lang.String self, java.lang.String other) {
            return self.compareTo(other);
        }
    }

    public static final class CICmp extends ss2I {
        @Override
        protected int f(java.lang.String self, java.lang.String other) {
            return self.compareToIgnoreCase(other);
        }
    }

    public static final class Substr extends sII2s {
        @Override
        protected java.lang.String f(java.lang.String self, int x, int y) {
            return self.substring(x,y);
        }
    }

    public static final class ToString extends s2s {
        @Override
        protected java.lang.String f(FString self) {
            return self.toString();
        }
    }

    public static final class Index extends sI2C {
        @Override
        protected char f(java.lang.String self, int i) {
            return self.charAt(i);
        }
    }

    public static final class Concat extends ss2S {
        @Override
        protected java.lang.String f(java.lang.String x, java.lang.String y) {
            return x + y;
        }
    }

    public static final class IndexOf extends sC2I {
        @Override
        protected int f(java.lang.String s, int c) {
            return s.indexOf((char)c);
        }
    }

 }
