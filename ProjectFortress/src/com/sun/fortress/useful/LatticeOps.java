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

package com.sun.fortress.useful;

public interface LatticeOps<U> {
    /**
     * Returns true if the lattice is used in its usual forward sense,
     * rather than its dual sense.
     */
    boolean isForward();

    /**
     * Returns bottom element of lattice.
     * Bottom, least general, empty set.
     */
    U zero();

    /**
     * Returns top element of lattice.
     * Top, most general, set of all.
     */
    U one();

    /**
     * Meet, Square Cap, Greatest Lower Bound, Infimum, more specific, intersection.
     * <p/>
     * lattice.meet(lattice.one(), X) == X.
     */
    U meet(U x, U y);

    /**
     * Join, Square Cup, Least Upper Bound, Supremum, more general, union.
     * <p/>
     * lattice.join(lattice.zero(), X) == X.
     */

    U join(U x, U y);

    LatticeOps<U> dual();
}
