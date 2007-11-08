/*******************************************************************************
    Copyright 2007 Sun Microsystems, Inc.,
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

package com.sun.fortress.interpreter.evaluator.types;

import com.sun.fortress.nodes_util.NodeUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.sun.fortress.interpreter.env.BetterEnv;
import com.sun.fortress.interpreter.evaluator.Environment;
import com.sun.fortress.interpreter.evaluator.values.FValue;
import com.sun.fortress.nodes.IdType;
import com.sun.fortress.nodes.VarargsType;
import com.sun.fortress.nodes.StaticParam;
import com.sun.fortress.nodes.Type;
import com.sun.fortress.useful.BASet;
import com.sun.fortress.useful.BoundingMap;
import com.sun.fortress.useful.EmptyLatticeIntervalError;
import com.sun.fortress.useful.HasAt;
import com.sun.fortress.useful.ListComparer;
import com.sun.fortress.useful.MagicNumbers;
import com.sun.fortress.useful.Pair;
import com.sun.fortress.useful.Useful;

import static com.sun.fortress.interpreter.evaluator.ProgramError.errorMsg;
import static com.sun.fortress.interpreter.evaluator.ProgramError.error;
import static com.sun.fortress.interpreter.evaluator.values.OverloadedFunction.exclDump;
import static com.sun.fortress.interpreter.evaluator.values.OverloadedFunction.exclDumpln;

abstract public class FType implements Comparable<FType> {

    protected static final boolean DUMP_UNIFY = false;

    static public Comparator<FType> comparator = new Comparator<FType>() {

        public int compare(FType arg0, FType arg1) {
            return arg0.compareTo(arg1);
        }

    };

    static public ListComparer<FType> listComparer = new ListComparer<FType>();
    
    // static Random random = new Random(0xd06f00d);

    /* (non-Javadoc)
     * @see java.lang.Comparable#compareTo(java.lang.Object)
     */
    public int compareTo(FType arg0) {
        return serial - arg0.serial; /* Assume fewer than a billion types, hence no overflow. */
    }

    final String name;
    private final int serial;
    private final int hash;
    private BASet<FType> excludes = new BASet<FType>(comparator);
    private static int counter;
    protected volatile List<FType> transitiveExtends;
   // Must be volatile due to lazy initialization / double-checked locking.

    private List<Pair<HasAt, FType> > mustExtend;
    // Where clauses in superclasses parameterized by Self can
    // introduce constraints that must be satisfied.

    protected boolean isSymbolic;
    protected boolean cannotBeExtended;

    public synchronized void resetState() {
        transitiveExtends = null;
        excludes = new BASet<FType>(comparator);
    }

    public final boolean isSymbolic() {
        return isSymbolic;
    }

    public final boolean cannotBeExtended() {
        return cannotBeExtended;
    }

    public int hashCode() {
        return hash;
    }

    public String getName() {
        return name;
    }

    protected FType(String s) {
        name = s;
        synchronized(FType.class) {
            serial = ++counter;
        }
        hash = MagicNumbers.uniformHash(serial, MagicNumbers.F, MagicNumbers.T);
    }

    /**
     * Returns true iff val is a member of a (improper) subtype
     * of this type.
     */
    public boolean typeMatch(FValue val) {
        // System.out.println(name+".typeMatch("+val+")");
        if (val == null)
            return false;
        if (val.type() == null)
            return false;
        return val.type().subtypeOf(this);
    }

    public String toString() {
        return name;
    }

    public List<FType> getExtends() {
        return Collections.emptyList();
    }

    protected List<FType> getExtendsNull() {
        return getExtends();
    }

    protected List<FType> computeTransitiveExtends() {
        return Useful.<FType>list(this);
    }

    /**
     * Note that transitive extends includes this type.
     * Traits and objects do something more complex.
     */
    public List<FType> getTransitiveExtends() {
        if (transitiveExtends == null) {
            List<FType> tmp = computeTransitiveExtends();
            synchronized (this) {
                if (transitiveExtends == null) {
                    transitiveExtends = tmp;
                }
            }
        }
        return transitiveExtends;
    }

    public Set<FType> getExcludes() {
        return excludes.copy();
    }

    public void addExclude(FType t) {
        this.addExcludesInner(t);
        t.addExcludesInner(this);
    }

    private void addExcludesInner(FType t) {
        if (t == this)
            error(errorMsg("Type cannot exclude itself: ", t));
        excludes.syncPut(t);
    }

    public boolean excludesOther(FType other) {
        if (other instanceof FTypeArrow) // FTypeArrow handles other case
            return true;
        if (other instanceof FTypeTuple) // FTypeTuple handles other case
            return true;
        else return excludesOtherInner(other);
    }

    private boolean excludesOtherInner(FType other) {
        if (this == other) {
            exclDumpln("No.  Equal.");
            return false;
        }

        // If neither type can be extended, then they exclude.

        // If one cannot be extended, and the other is not an
        // actual super, then they exclude.

        // Otherwise, look for exclusion in the supertypes.
        if (cannotBeExtended()) {
            if (other.cannotBeExtended) {
                exclDumpln("Excludes.  Neither can be extended.");
                return true;
            }

            // Optimization hack -- check memoized exclusion before doing work.
            if (excludes.contains(other)) {
                exclDumpln("Excludes (cached).");
                return true;
            }

            if (getTransitiveExtends().contains(other)) {
                exclDumpln("No.  Transitive extend contains.");
                return false;
            }

            exclDumpln("Excludes.  Non-extensible + no supertype");
            // Memoize
            this.addExclude(other);
            return true;


        } else if (other.cannotBeExtended()) {
            // Optimization hack -- check memoized exclusion before doing work.
            if (excludes.contains(other)) {
                exclDumpln("Excludes (cached').");
                return true;
            }

            if (other.getTransitiveExtends().contains(this)) {
                exclDumpln("No.  Contains transitive extend.");
                return false;
            }

            exclDumpln("Excludes.  No supertype + non-extensible");
            // Memoize
            this.addExclude(other);
            return true;

        }

        // Not necessarily an optimization hack here; this is part of
        // the definition, but it also probes memoized results.

        if (excludes.contains(other)) {
            exclDumpln("Excludes (cached\").");
            return true;
        }

        if (other.getExcludes().contains(this)) {
            exclDumpln("Excludes (other declared).");
            this.addExclude(other);
            return true;
        }

        List<FType> extendsThis = getTransitiveExtends();
        for (FType t : extendsThis) {
            BASet t_excludes = t.excludes;
            if (t_excludes.isEmpty())
                continue;
            List<FType> extendsOther = other.getTransitiveExtends();
            for (FType o : extendsOther) {
                if ( !(t == this && o == other) && t_excludes.contains(o)) {
                    // Short-circuit any future queries
                    exclDumpln("Excludes via ",t," and ",o);
                    this.addExclude(o);
                    if (o!=other) this.addExclude(other);
                    return true;
                }
            }
        }
        exclDumpln("No.");
        return false;

    }

    public BetterEnv getEnv() {
        return BetterEnv.blessedEmpty();
    }

    protected final boolean commonSubtypeOf(FType other) {
        return (this == other || other == FTypeDynamic.ONLY || other==FTypeTop.ONLY);
    }

    /**
     * Returns "this subtypeof other"
     */
    @SuppressWarnings("unchecked")
    public boolean subtypeOf(FType other) {
        if (other == FTypeDynamic.ONLY || other == FTypeTop.ONLY)
            return true;
        Class us = getClass();
        Class them = other.getClass();
        if (us == them) return true;
        if (other == BottomType.ONLY)
            return false;
        // TODO There's some type instantiations missing, but I do not know what
        // good ones would be, and the code is correct anyway.
        return them.isAssignableFrom(us);
    }

    public boolean equals(Object other) {
        return this == other;
//        if (this == other) return true;
//        if (! (other instanceof FType)) return false;
//        return this.getClass().equals(other.getClass());
    }

    /* Sadly, meet needs an iteration similar to join below, but lacks
     * the necessary subclass information. */
    public Set<FType> meet(FType t2) {
        if (this.equals(t2)) {
            return Useful.set(this);
        } else if (this.subtypeOf(t2)) {
            // Meet of X with dynamic is X.
            return Useful.set(this);
        } else if (t2.subtypeOf(this)) {
            return Useful.set(t2);
        }
        return Collections.emptySet();
    }

    public Set<FType> join(FType t2) {
        if (this.equals(t2)) {
            return Useful.set(this);
        } else if (t2.subtypeOf(this)) {
            // Join of x with dynamic is X.
            return Useful.set(this);
        } else if (this.subtypeOf(t2)) {
            return Useful.set(t2);
        } else {
            Set<FType> result = Collections.emptySet();
            for (FType candidate : getTransitiveExtends()) {
                if (!t2.subtypeOf(candidate)) continue;
                TreeSet<FType> newresult = new TreeSet<FType>();
                boolean addC = true;
                for (FType r : result) {
                    if (r.subtypeOf(candidate)) {
                        addC = false;
                        break;
                    } else if (candidate.subtypeOf(r)) {
                        /* Drop r */
                    } else {
                        newresult.add(r);
                    }
                }
                if (addC) {
                    newresult.add(candidate);
                    result = newresult;
                }
            }
            return result;
        }
    }

    public static Set<FType> join(List<FValue> evaled) {
        // A is the accumulated set of joined items.
        Set<FType> a = null;
        if (evaled.size() > 0) {
            for (FValue v: evaled) {
                // for each value, join in the value's type.
                TreeSet<FType> b = new TreeSet<FType>();
                FType vt = v.type();
                if (a == null)
                    b.add(vt);
                else {
                    // For each type in the accumulator, do the
                    // join with the next element, accumulating in b.
                    for (FType t : a) {
                        if (b.size() == 0)
                            b.add(t);
                        else
                            b.addAll(t.join(vt));
                    }
                    // b is our new "a".

                }
                a = b;

            }
        } else {
            // Empty set, best we can do
            a = FTypeDynamic.SingleT;
        }
        return a;
    }

     public FType deRest() {
        return this;
    }

    /**
     * @param candidate
     * @param current
     * @return
     */
    private static boolean definitelyShorterThan(List<FType> candidate, List<FType> current) {
        int sz = candidate.size();
        return sz < current.size() &&
            (sz==0 || !(candidate.get(sz-1) instanceof FTypeRest));
    }

    protected boolean unifyNonVar(BetterEnv env, Set<StaticParam> tp_set,
            BoundingMap<String, FType, TypeLatticeOps> abm, Type val) {
        boolean rc;
        if (! (val instanceof IdType)) {
            rc = false;
        } else if (name.equals(NodeUtil.nameString(((IdType)val).getName()))) {
            rc = true;
        } else {
            FType other = env.getTypeNull(((IdType)val).getName());

            if (other == null) {
                rc = false;
            } else {
                // Let the unification succeed if there's a subtype relationship.
                rc = this.subtypeOf(other);
            }
        }
        if (DUMP_UNIFY) {
            System.out.println("unify FType "+this+" and "+val + (rc ? " OK " : " NO ") + "("+this.getClass().getSimpleName()+
                    "), abm="+abm);
        }

        return rc;
    }

    /** One-sided unification of this fully-computed FType with a signature.
     * @param tp_set  The type variables which are subject to unification.
     * @param abm     Map of bounds on these variables (if any), updated.
     * @param val   The signature to be unified with.
     *
     * Does most of the boilerplate work of unification, including
     * variable unification and supertype chasing (in topological
     * order).  The stuff which varies between types is found in
     * unifyNonVar; this is why that method is overridable while this
     * one is final.
     */
    public final void unify(BetterEnv env, Set<StaticParam> tp_set, BoundingMap<String, FType, TypeLatticeOps> abm, Type val) {
        /* Check if val is a type variable */
        if (val instanceof IdType) {
            IdType id_val = (IdType) val;
            String nm = NodeUtil.nameString(id_val.getName());
//            for (StaticParam tp : tp_set) {
//                String k = NodeUtil.getName(tp);
//                if (k.equals(nm)) {
                    if (DUMP_UNIFY) System.out.print("Trying "+nm+"="+this);
                    try {
                       abm.joinPut(nm, this);
                    } catch (EmptyLatticeIntervalError el) {
                        if (DUMP_UNIFY) System.out.println("Out of bounds");
                        error(errorMsg("Actual type ",this,
                                       " out of bounds for variable ",nm));
                        return;
                    } catch (Error th) {
                        if (DUMP_UNIFY) System.out.println(" fail " + th.getMessage());
                        throw th;
                    } catch (RuntimeException th) {
                        if (DUMP_UNIFY) System.out.println(" fail " + th.getMessage());
                        throw th;
                    }
                    if (DUMP_UNIFY) System.out.println(" result abm= " + abm);
                    return;
//                }
//            }
        }
        /* We want to unify with the most specific subtype possible, so */
        BoundingMap<String,FType,TypeLatticeOps> savedAbm = abm.copy();
        for (FType t : getTransitiveExtends()) {
            if (t.unifyNonVar(env, tp_set, abm, val)) return;
            if (DUMP_UNIFY) System.out.println("            "+t+" !=  "+val+", abm=" + abm);
            abm.assign(savedAbm);
        }
        error(val,env,
              errorMsg("Cannot unify ",
                       this,
                       "(",
                       this.getClass(),
                       ")\n  with ",
                       val,
                       "(",
                       val.getClass(),
                       ") abm=" + abm
                       ));
    }

    /**
     * Convenience method for unifying with a VarargsType (e.g., "T...").
     * VarargsTypes are special forms that appear only in TupleTypes. They are not Types.
     */
    public final void unify(BetterEnv env, Set<StaticParam> tp_set, BoundingMap<String, FType, TypeLatticeOps> abm, VarargsType val) {
        unify(env, tp_set, abm, val.getType());
    }

    public void mustExtend(FType st, HasAt constraint_loc) {
        List<FType> curr_extends = getExtendsNull();
        if (curr_extends == null) {

            if (mustExtend == null)
                mustExtend = new ArrayList<Pair<HasAt, FType>>();

            mustExtend.add(new Pair<HasAt, FType>(constraint_loc, st));
        } else {
            if (!subtypeOf(st)) {
                error(constraint_loc,
                      errorMsg("", this, " must subtype ", st));
            }
        }
    }

    protected void checkConstraints() {
        if (mustExtend != null) {
            String failures = "";
            for (Pair<HasAt, FType> p : mustExtend)
                if (!subtypeOf(p.getB())) {
                    String failure = errorMsg("At ", p.getA(), " ", this, " must subtype ", p.getB());
                    if (failures.length() == 0)
                        failures = failure;
                    else
                        failures = failures + "\n" + failure;
                }
            if (failures.length() > 0)
                error(failures);
        }
        mustExtend = null;
    }

    public static boolean anyAreSymbolic(List<FType> args) {
        for (FType t : args)
            if (t.isSymbolic())
                return true;
        return false;
    }
}
