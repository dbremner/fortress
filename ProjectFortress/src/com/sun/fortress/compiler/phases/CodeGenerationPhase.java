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

package com.sun.fortress.compiler.phases;

import com.sun.fortress.compiler.AnalyzeResult;
import com.sun.fortress.compiler.codegen.*;
import com.sun.fortress.compiler.environments.TopLevelEnvGen;
import com.sun.fortress.exceptions.MultipleStaticError;
import com.sun.fortress.exceptions.StaticError;
import com.sun.fortress.nodes.Component;
import com.sun.fortress.useful.Debug;


import edu.rice.cs.plt.iter.IterUtil;

public class CodeGenerationPhase extends Phase {

    public CodeGenerationPhase(Phase parentPhase) {
        super(parentPhase);
    }

    @Override
    public AnalyzeResult execute() throws StaticError {
        Debug.debug(Debug.Type.FORTRESS, 1, "Start phase CodeGeneration");
        AnalyzeResult previous = parentPhase.getResult();

        TopLevelEnvGen.CompilationUnitResult apiGR = TopLevelEnvGen
                .generateApiEnvs(previous.apis());

        if (!apiGR.isSuccessful()) {
            throw new MultipleStaticError(apiGR.errors());
        }

        // Generate top-level byte code environments for components
        TopLevelEnvGen.CompilationUnitResult componentGR = TopLevelEnvGen
                .generateComponentEnvs(previous.components());

        if (!componentGR.isSuccessful()) {
            throw new MultipleStaticError(componentGR.errors());
        }

        // Generate bytecodes for as much as we can.

        Debug.debug(Debug.Type.CODEGEN, 1, "Before invoking Compile: components=" + previous.components());
        //Debug.debug(Debug.Type.CODEGEN, 1, "Before invoking Compile: apis      =" + previous.apis());

        for (Component comp : previous.componentIterator()) {
            Debug.debug(Debug.Type.CODEGEN, 1, "CodeGenerationPhase: Compile(" + comp.getName() + ")");
            String compName = comp.getName().getText();
            if (compName.equals("Compile0")) {
                Compile c = new Compile(comp.getName().getText());
                System.out.println("CodeGenerationPhase: name = " + comp.getName().getText() + " comp = " + comp + " Compile = " + c);
                comp.accept(c);
            }
        }

        return new AnalyzeResult(previous.apis(), previous.components(),
                                 IterUtil.<StaticError> empty(),
                                 previous.typeCheckerOutput());

    }

}
