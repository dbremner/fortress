/*******************************************************************************
 Copyright 2008 Sun Microsystems, Inc.,
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

package com.sun.fortress.ant_tasks;

public class FortressTask extends BatchTask {

    public FortressTask() {
        super("fortress");
    }

    public void setCompile(boolean val) {
        addExecOption("compile");
    }

    public void setAst(boolean val) {
        addExecOption("-ast");
    }

    public void setKeep(boolean val) {
        addExecOption("-keep");
    }

    public void setPause(boolean val) {
        addExecOption("-pause");
    }

    public void setParse(boolean val) {
        addExecOption("parse");
    }

    public void setNolib(boolean val) {
        addExecOption("-nolib");
    }

    public void setVerbose(boolean val) {
        addExecOption("-v");
    }

    public void setTest(boolean val) {
        addExecOption("-test");
    }

}
