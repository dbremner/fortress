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

package com.sun.fortress.syntax_abstractions.phases;

import com.sun.fortress.nodes.Id;

public class SyntaxEnvironment {
    private SyntaxEnvironment previous;
    private Id from;
    private Id to;

    private SyntaxEnvironment( SyntaxEnvironment previous, Id from, Id to ){
        this.previous = previous;
        this.from = from;
        this.to = to;
    }

    public SyntaxEnvironment extend( Id from, Id to ){
        return new SyntaxEnvironment( this, from, to );
    }

    public Id lookup( Id who ){
        if ( who.equals(from) ){
            return to;
        }
        return previous.lookup( who );
    }

    public String toString(){
        return from + " -> " + to + ", " + previous;
    }

    public static SyntaxEnvironment identityEnvironment(){
        return new SyntaxEnvironment(null, null, null){
            @Override
            public Id lookup( Id who ){
                return who;
            }

            public String toString(){
                return "identity";
            }
        };
    }

}
