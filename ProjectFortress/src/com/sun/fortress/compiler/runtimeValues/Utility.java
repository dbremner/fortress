/*******************************************************************************
 Copyright 2011 Oracle and/or its affiliates.
 All rights reserved.


 Use is subject to license terms.

 This distribution may include materials developed by third parties.

 ******************************************************************************/

package com.sun.fortress.compiler.runtimeValues;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import com.sun.fortress.compiler.runtimeValues.FException;
import com.sun.fortress.compiler.runtimeValues.FString;
import com.sun.fortress.compiler.runtimeValues.FValue;
import com.sun.fortress.compiler.runtimeValues.FortressImplementationError;

public final class Utility {

    public static FException makeFortressException(String name, Throwable e) {
	return makeFortressException(name, e.getMessage());
    }

    public static FException makeFortressException(String name, String msg) {
	try {
	    Constructor fortressIOException = Class.forName(name).getConstructor(new Class[]{ String.class });
	    return new FException((FValue)fortressIOException.newInstance(FString.make(msg == null ? "" : msg)));
	} catch (ClassNotFoundException x) {
	    throw new FortressImplementationError(x);
	} catch (NoSuchMethodException x) {
	    throw new FortressImplementationError(x);
	} catch (InstantiationException x) {
	    throw new FortressImplementationError(x);
	} catch (IllegalAccessException x) {
	    throw new FortressImplementationError(x);
	} catch (InvocationTargetException x) {
	    throw new FortressImplementationError(x);
	}
    }

    public static FException makeFortressException(String name) {
	try {
	    Constructor fortressIOException = Class.forName(name).getConstructor(new Class[]{ String.class });
	    return new FException((FValue)fortressIOException.newInstance(FString.make("")));
	} catch (ClassNotFoundException x) {
	    throw new FortressImplementationError(x);
	} catch (NoSuchMethodException x) {
	    throw new FortressImplementationError(x);
	} catch (InstantiationException x) {
	    throw new FortressImplementationError(x);
	} catch (IllegalAccessException x) {
	    throw new FortressImplementationError(x);
	} catch (InvocationTargetException x) {
	    throw new FortressImplementationError(x);
	}
    }

}
