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
import com.sun.fortress.compiler.GlobalEnvironment;
import com.sun.fortress.compiler.PreDisambiguationDesugarer;
import com.sun.fortress.exceptions.MultipleStaticError;
import com.sun.fortress.exceptions.StaticError;
import com.sun.fortress.useful.Debug;
import edu.rice.cs.plt.collect.CollectUtil;
import edu.rice.cs.plt.iter.IterUtil;

/**
 * A phase for running desugarings that must occur before Disambiguation.
 */
public class PreDisambiguationDesugarPhase extends Phase {

    public PreDisambiguationDesugarPhase(Phase parentPhase) {
        super(parentPhase);
    }

    @Override
    public AnalyzeResult execute() throws StaticError {
        Debug.debug(Debug.Type.FORTRESS, 1, "Start phase Pre-Disambiguation Desugar");
        AnalyzeResult previous = parentPhase.getResult();

//            GlobalEnvironment apiEnv = new GlobalEnvironment.FromMap(CollectUtil
//                                                                     .union(repository.apis(),
//                                                                            CollectUtil.union(env.apis(),
//                                                                                              previous.apis())));

        GlobalEnvironment apiEnv = new GlobalEnvironment.FromMap(CollectUtil.union(env.apis(), previous.apis()));

//              System.err.println("PreDisambiguateDesugarPhase apiEnv:");
//              apiEnv.print();
//              System.err.println("PreDisambiguateDesugarPhase end apiEnv");


        PreDisambiguationDesugarer.ApiResult apiDSR = PreDisambiguationDesugarer.desugarApis(previous.apis(),
                apiEnv);

        if (!apiDSR.isSuccessful()) {
            throw new MultipleStaticError(apiDSR.errors());
        }

        PreDisambiguationDesugarer.ComponentResult componentDSR = PreDisambiguationDesugarer.desugarComponents(
                previous.components(), apiEnv);

        if (!componentDSR.isSuccessful()) {
            throw new MultipleStaticError(componentDSR.errors());
        }

        return new AnalyzeResult(apiDSR.apis(), componentDSR.components(),
                IterUtil.<StaticError>empty(), previous.typeCheckerOutput());
    }

}
