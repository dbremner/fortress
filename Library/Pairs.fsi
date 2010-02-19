(*******************************************************************************
    Copyright 2010 Sun Microsystems, Inc.,
    4150 Network Circle, Santa Clara, California 95054, U.S.A.
    All rights reserved.

    U.S. Government Rights - Commercial software.
    Government users are subject to the Sun Microsystems, Inc. standard
    license agreement and applicable provisions of the FAR and its supplements.

    Use is subject to license terms.

    This distribution may include materials developed by third parties.

    Sun, Sun Microsystems, the Sun logo and Java are trademarks or registered
    trademarks of Sun Microsystems, Inc. in the U.S. and other countries.
 ******************************************************************************)

api Pairs

import List.{...}
import Map.{...}

pairs[\T\](g: Generator[\T\]): Generator[\(T,T)\]
triples[\T\](g: Generator[\T\]): Generator[\(T,T,T)\]
runRanges(x: Indexed[\Boolean,ZZ32\]): List[\CompactFullRange[\ZZ32\]\]
geometricMean(xs: List[\RR64\]): RR64

opr UNIONCAT[\T,U\](a: Map[\T, List[\U\]\], b: Map[\T, List[\U\]\]): Map[\T, List[\U\]\]
opr BIG UNIONCAT[\T,U\](): BigReduction[\Map[\T, List[\U\]\],Map[\T, List[\U\]\]\]

opr UNIONPLUS[\T\](a: Map[\T, ZZ32\], b: Map[\T, ZZ32\]): Map[\T, ZZ32\]
opr BIG UNIONPLUS[\T\](): BigReduction[\Map[\T, ZZ32\],Map[\T, ZZ32\]\]

end
