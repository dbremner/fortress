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

package com.sun.fortress.interpreter.evaluator.values;

import com.sun.fortress.interpreter.evaluator.Environment;


public abstract class FObject extends FValue implements Selectable {
    /**
     * The environment that you get from "self."
     */
    public abstract Environment getSelfEnv();

    public abstract Environment getLexicalEnv();

    public FValue select(String s) {
        return getSelfEnv().getLeafValue(s);
    }

    public FValue selectField(String s) {
        return getSelfEnv().getLeafValue("$" + s);
    }

    public String getString() {
        return type().toString();
    }

    public String toString() {
        return getString();
    }
}
