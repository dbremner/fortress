(*******************************************************************************
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
 ******************************************************************************)

api QuickSort
import List.{List}

(** \url{http://en.wikipedia.org/wiki/Quicksort} *)
quicksort[\T\](lt:(T,T)->Boolean, arr:Array[\T,ZZ32\], left:ZZ32, right:ZZ32):()
quicksort[\T\](lt:(T,T)->Boolean, arr:Array[\T,ZZ32\]):()
quicksort[\T extends StandardTotalOrder[\T\]\](arr:Array[\T,ZZ32\]):()
quicksort[\T\](lt:(T,T)->Boolean, xs:List[\T\]):List[\T\]
quicksort[\T\](xs:List[\T\]):List[\T\]

end
