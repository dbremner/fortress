/*******************************************************************************
    Copyright 2009,2011, Oracle and/or its affiliates.
    All rights reserved.


    Use is subject to license terms.

    This distribution may include materials developed by third parties.

 ******************************************************************************/

package com.sun.fortress.compiler;

import java.util.*;

import org.objectweb.asm.*;
import org.objectweb.asm.util.*;

import com.sun.fortress.compiler.codegen.CodeGen;
import com.sun.fortress.compiler.codegen.CodeGenClassWriter;
import com.sun.fortress.compiler.index.Constructor;
import com.sun.fortress.compiler.index.DeclaredFunction;
import com.sun.fortress.compiler.index.DeclaredMethod;
import com.sun.fortress.compiler.index.FieldGetterOrSetterMethod;
import com.sun.fortress.compiler.index.Functional;
import com.sun.fortress.compiler.index.FunctionalMethod;
import com.sun.fortress.scala_src.overloading.OverloadingOracle;
import com.sun.fortress.scala_src.typechecker.Formula;
import com.sun.fortress.scala_src.types.TypeAnalyzer;
import com.sun.fortress.scala_src.types.TypeSchemaAnalyzer;
import com.sun.fortress.scala_src.useful.STypesUtil;
import com.sun.fortress.compiler.phases.CodeGenerationPhase;
import com.sun.fortress.exceptions.CompilerError;
import com.sun.fortress.nodes.*;
import com.sun.fortress.nodes.Type;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.runtimeSystem.InitializedStaticField;
import com.sun.fortress.runtimeSystem.InstantiatingClassloader;
import com.sun.fortress.runtimeSystem.Naming;
import com.sun.fortress.useful.*;

import edu.rice.cs.plt.tuple.Option;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import scala.collection.JavaConversions;

abstract public class OverloadSet implements Comparable<OverloadSet> {
    
    
    static class POType extends TopSortItemImpl<Type> {
        public POType(Type x) {
            super(x);
        }
    }

    static class POTFN extends TopSortItemImpl<TaggedFunctionName> {
        public POTFN(TaggedFunctionName x) {
            super(x);
        }
    }

    public static class TaggedFunctionName implements Comparable<TaggedFunctionName> {
        final private APIName tagA;
        final private Functional tagF;

        public TaggedFunctionName(APIName a, Functional f) {
            this.tagF = f;
            this.tagA = a;
        }

        public Functional getF() {
            return tagF;
        }
        
        public List<Param> getParameters() {
            return tagF.parameters();
        }

        public Type getReturnType() {
            return tagF.getReturnType().unwrap();
        }

        public int hashCode() {
            return tagF.hashCode() + MagicNumbers.a * tagA.hashCode();
        }

        public boolean equals(Object o) {
            if (o instanceof TaggedFunctionName) {
                TaggedFunctionName tfn = (TaggedFunctionName) o;
                return tagF.equals(tfn.tagF) && tagA.equals(tfn.tagA);
            }
            return false;
        }

        public List<Type> getThrownTypes() {
            return tagF.thrownTypes();
        }

        public String toString() {
            return tagA.toString() + ".." + tagF.toString();
        }

        @Override
        public int compareTo(TaggedFunctionName o) {
            int i = tagF.toUndecoratedName().toString().compareTo(o.tagF.toUndecoratedName().toString());
            if (i != 0) return i;
            i = tagA.toString().compareTo(o.tagA.toString());
            if (i != 0) return i;
            List<Param> p = getParameters();
            List<Param> q = o.getParameters();
            if (p.size() < q.size()) return -1;
            if (p.size() > q.size()) return 1;
            for (int j = 0; j < p.size(); j++) {
                Param pp = p.get(j);
                Param qq = q.get(j);
                if (! (pp.getIdType().unwrap() instanceof Type &&
                       qq.getIdType().unwrap() instanceof Type) )
                    throw new CompilerError("Types are expected.");
                Type tp = (Type)pp.getIdType().unwrap();
                Type tq = (Type)qq.getIdType().unwrap();
                Class cp = tp.getClass();
                Class cq = tq.getClass();
                if (cp.equals(cq)) {
                    i = tp.toString().compareTo(tq.toString());
                } else {
                    i = cp.getName().compareTo(cq.getName());
                }
                if (i != 0)
                    return i;
            }
            return 0;
        }
    }


    /**
     * The set of functions that are less-specific-than-or-equal to the
     * parameters seen so far, so the parameter seen so far would be legal
     * for any of them.  The goal is to thin this set as more parameters
     * are found, and ultimately to choose the most specific one that
     * remains.
     */
    final Set<TaggedFunctionName> lessSpecificThanSoFar;
    final IdOrOpOrAnonymousName name;
    
    /**
     * The functions, ordered from most to least specific order.
     * This is used to obtain a dispatch order in the presence
     * of generics (it also works when there are no generics).
     */
    TaggedFunctionName[] specificDispatchOrder;
    
    /**
     * This overloaded function may have a member whose signature matches
     * the overloaded function's signature.  If so, the principalMember is not
     * null.
     */
    TaggedFunctionName principalMember;
    public String genericSchema = ""; /* Need to stash this for generic-tagged overloads. */

    /**
     * Every function in the set of overloads is a principal member of a smaller
     * (possibly singleton) overload set.  Overloaded functions need to be
     * compiled for the non-singleton subsets if the overloaded function is
     * exported (i.e., is not specific to a use site in a component).
     * 
     * Note that if the entire set has a principal member, then it also appears
     * in this subset, because that indicates that the principal member
     * (a single function that may be dispatched to) needs a local-mangled name.
     * 
     * Thus, when iterating over the values in this set to generate code,
     * it is necessary to guard against generating code for the outermost
     * overloaded set.
     * 
     * Uses a comparator that takes parameter lists into account.
     */
    private BATree<String, OverloadSet> overloadSubsets =
        new BATree<String, OverloadSet>(DefaultComparator.<String>normal());

    /**
     * Used to answer subtype questions.
     */
    final TypeAnalyzer ta;
    final OverloadingOracle oa;

    /**
     * Assuming we have a parent (are not the root of a tree of OverloadSets),
     * what is that parent.
     */
    final OverloadSet parent;
 
    final int paramCount;

    final APIName ifNone;
    final String packageAndClassName;

    /**
     * Which parameter is used to split this set into subsets?
     */
    OverloadSet[] children;
    boolean splitDone;
    
    /* in the event that the overloaded function is generic, these will be non-null after "split" */
    List<StaticParam> static_parameters = null;
    Naming.XlationData xldata = null; 
    String PCN = null;

    protected OverloadSet(APIName ifNone, IdOrOpOrAnonymousName name, TypeAnalyzer ta, OverloadingOracle oa,
                          Set<OverloadSet.TaggedFunctionName> lessSpecificThanSoFar,
                          OverloadSet parent, int paramCount) {
        this.ifNone = ifNone;
        this.packageAndClassName = NamingCzar.javaPackageClassForApi(ifNone);
        this.name = name;
        this.ta = ta;
        this.oa = oa;
        this.lessSpecificThanSoFar = lessSpecificThanSoFar;
        this.parent = parent;
        this.paramCount = paramCount;
    }

    protected OverloadSet(IdOrOpOrAnonymousName name, TypeAnalyzer ta, OverloadingOracle oa,
                          Set<OverloadSet.TaggedFunctionName> lessSpecificThanSoFar,
                          int paramCount, APIName ifNone) {
        this(ifNone, name, ta, oa, lessSpecificThanSoFar, 
                null, paramCount);
    }

    protected OverloadSet(final APIName apiname, IdOrOpOrAnonymousName name, TypeAnalyzer ta, OverloadingOracle oa, Set<Functional> defs, int n) {

        this(name, ta, oa, Useful.applyToAll(defs, new F<Functional, TaggedFunctionName>() {

            @Override
            public TaggedFunctionName apply(Functional f) {
                return new TaggedFunctionName(apiname, f);
            }
        }), n, apiname);

        // Ensure that they are all the same size.
        for (TaggedFunctionName f : lessSpecificThanSoFar) {
            if (CodeGenerationPhase.debugOverloading)
                System.err.println("Overload: " + f);
            List<Param> parameters = f.getParameters();
            int this_size = parameters.size();
            if (this_size != paramCount)
                throw new CompilerError("Need to handle variable arg dispatch elsewhere " + name);

        }
    }

    abstract protected void invokeParticularMethod(MethodVisitor mv, TaggedFunctionName f,
                                                   String sig);

    /**
     * Creates a subset overload set; one that can be named independently as an overloaded function.
     *
     * @param childLSTSF
     * @param parent TODO
     * @param principalMember
     * @return
     */
    abstract protected OverloadSet makeSubset(Set<TaggedFunctionName> childLSTSF, TaggedFunctionName _principalMember, OverloadSet parent);

    public void split(boolean computeSubsets) {
        /* First determine if there are any overload subsets.
           This matters because it may affect naming
           of the leaf (single) functions.
         */
        if (CodeGenerationPhase.debugOverloading)
            System.err.println(" Split " + this);


        TopSortItemImpl<TaggedFunctionName>[] pofuns =
            new OverloadSet.POTFN[lessSpecificThanSoFar.size()];
        /* Convert set of dispatch types into something that can be
         * (topologically) sorted. */
        {
            int i = 0;   

            for (TaggedFunctionName f : lessSpecificThanSoFar ) {
                pofuns[i] = new POTFN(f);
                i++;
            }
        }

        for (int fi = 0; fi < pofuns.length; fi++) {
            TaggedFunctionName f = pofuns[fi].x;
            int i = 1; // for self
            /* Count the number of functions in LSTSF that are more
             * specific than or equal to f.
             * Also record any more-specific-than relationship encountered.
             */
            for (int gi = 0; gi < pofuns.length; gi++) {
                TaggedFunctionName g = pofuns[gi].x;
                if (!(f == g)) {
                    if (fLessSpecThanG(f, g)) {
                        if (CodeGenerationPhase.debugOverloading)
                            System.err.println(g.toString() + " is <= " + f.toString());
                        i++;
                        pofuns[gi].edgeTo(pofuns[fi]);
                    }
                }
            }
            if (computeSubsets) {

                if (i > 1) {
                    /* There's at least one function more specific than f. */
                    if (i == lessSpecificThanSoFar.size()) {
                        /*
                         * If f is more specific than or equal to every member
                         * of LSTSF, then it is the principal member.
                         */
                        principalMember = f;
                    } else {
                        /* 
                         * There are SOME members of the subset that are more
                         * specific than f; identify those, and create that
                         * subset.  f will be the principal member of the
                         * overloaded function that results.  External (through
                         * API) references to f, must invoke the overloaded
                         * function.
                         */
                        HashSet<TaggedFunctionName> subLSTSF =
                            new HashSet<TaggedFunctionName>();
                        subLSTSF.add(f);
                        for (TaggedFunctionName g : lessSpecificThanSoFar) {
                            if (!(f == g)) {
                                if (fLessSpecThanG(f, g)) {
                                    subLSTSF.add(g);
                                }
                            }
                        }
                        OverloadSet subset = makeSubset(subLSTSF, f, this);
                        subset.overloadSubsets = overloadSubsets;
                        String overload_name = subset.compute_overload_name(f);
                            // I don't think this key is right for generics.
                        overloadSubsets.put(overload_name , subset);
                    }
                }
            }
        }
        
        List<TopSortItemImpl<TaggedFunctionName>> specificFirst = TopSort.depthFirstArray(pofuns);
        
        if (computeSubsets) {
            /*
             * After identifying all the subsets (which have principal
             * members), generate their structure.
             */
            for (OverloadSet subset : overloadSubsets.values()) {
                subset.splitInternal(specificFirst);
            }
        }

        // I don't think this key is right for generics.
        if (principalMember != null) {
            String overload_name = compute_overload_name(principalMember);
            overloadSubsets.put(overload_name, this);
        }

        /* Split set into dispatch tree. */
        splitInternal(specificFirst);

    }


    private String compute_overload_name(TaggedFunctionName f) {
        String filtered_name = chooseName(name.stringName(), NodeUtil.nameSuffixString(name));
        static_parameters = staticParametersOf(f.tagF);
        if (static_parameters != null) {
            xldata = CodeGen.xlationData(Naming.FUNCTION_GENERIC_TAG);
            String sparamsType = NamingCzar.genericDecoration(static_parameters, xldata, ifNone);
            genericSchema =
                NamingCzar.makeArrowDescriptor(ifNone, overloadedDomain(), getRange());
            String packageAndClassName = NamingCzar.javaPackageClassForApi(ifNone);
            PCN =
                Naming.genericFunctionPkgClass(packageAndClassName, filtered_name,
                                                   sparamsType, genericSchema);
            return PCN;
        } 
        return name.stringName()+jvmSignatureFor(f);
    }

    private void splitInternal(List<TopSortItemImpl<TaggedFunctionName>> funsInSpecificOrder) {
        if (splitDone)
            return;

        int l = lessSpecificThanSoFar.size();
        
        specificDispatchOrder = new TaggedFunctionName[l];
        {
            int i = 0;

            for (TopSortItemImpl<TaggedFunctionName> tsii_f : funsInSpecificOrder) {
                TaggedFunctionName f = tsii_f.x;
                if (lessSpecificThanSoFar.contains(f))
                    specificDispatchOrder[i++] = f;
            }
        }
        
        if (l == 1) {
            splitDone = true;
            return;
            // If there are no other alternatives, then we are done.
        }
        
        splitDone = true;
    }

    /**
     * Returns true if the parameter list of g is more specific than or equal
     * to the parameter list of f.  If any of g's parameters fails to be more
     * specific than the corresponding parameter of f (using the
     * "tweakedSubtypeTest"), then returns false.
     * 
     * @param f
     * @param g
     * @return
     */
    private boolean fLessSpecThanG(TaggedFunctionName f, TaggedFunctionName g) {
        List<Param> msf_parameters = f.getParameters();
        List<Param> cand_parameters = g.getParameters();
        if (msf_parameters.size() != cand_parameters.size()) {
            throw new CompilerError("Diff length parameter lists, should not be possible");
        }
                
        boolean result =  oa.lteq(g.tagF, f.tagF);
        return result;
    }

    @Override
    public int compareTo(OverloadSet o) {
        return name.stringName().compareTo(o.name.stringName());
    }

    public IdOrOpOrAnonymousName getName() {
        return name;
    }

    public int getParamCount() {
        return paramCount;
    }

    /**
     * returns the code generation signature for the overloaded function.
     * (separation of concerns problem here -- taking joins of types,
     * and returning a target-platform (i.e., Java) signature).
     *
     * @return
     */
    public String getSignature() {
        return getSignature(-1);
    }
    protected int selfIndex() {
        return -1;
    }
    public String getSignature(int param_to_skip) {
        if (principalMember != null)
            return jvmSignatureFor(principalMember);
        return overloadedDomainSig(param_to_skip) + NamingCzar.jvmTypeDesc(getRange(), ifNone);
    }

    public Type getRange() {
        if (principalMember != null) {
            TaggedFunctionName f = principalMember;
            return STypesUtil.insertStaticParams(normalizeSelfType(f.getReturnType()), f.tagF.staticParameters());
        } else {
        List<Type> typesToJoin = new ArrayList(lessSpecificThanSoFar.size());
        for (TaggedFunctionName f : lessSpecificThanSoFar) {
            List<StaticParam> sparams = f.tagF.staticParameters();
            typesToJoin.add(STypesUtil.insertStaticParams(normalizeSelfType(f.getReturnType()), sparams));
        }
        return join(ta,typesToJoin);
        }
    }

    /**
     * Type-level join that erases to Any if it obtains a Union type.
     *
     * TODO: should really erase to concrete least upper bound in type hierarchy,
     * but that requires extending TypeAnalyzer.
     */
    private static Type join(TypeAnalyzer ta, List<Type> tys) {
        TypeSchemaAnalyzer tsa = new TypeSchemaAnalyzer(ta);

        int l = tys.size();
        Type r = tys.get(0);
        if (l > 1) 
         for (Type rr : tys.subList(1, l)) {
             r = tsa.joinED(r, rr);
         }
            
        //    ta.join(JavaConversions.asIterable(tys));
        if (r instanceof UnionType) {
            r = NodeFactory.makeAnyType(r.getInfo().getSpan());
        }
        return r;
    }

    /**
     * Given an intersection of arrow types (what we get for at least some
     * overloaded functions) and an indication of how many parameters actually
     * appeared, create the signature for the overloaded function that will be
     * called.
     * 
     * Also needs an appropriate  TypeAnalyzer
     * <p/>
     * There's some fiddliness with varargs that has to be sorted out.
     *
     * @param t
     * @param paramCount
     * @param ta
     * @return
     */
    public static String getSignature(IntersectionType t, int paramCount, TypeAnalyzer ta) {

        String s = overloadedDomainSig(t, paramCount, ta);

        Type r = getRange(t, paramCount, ta);

        s += NamingCzar.jvmTypeDesc(r, null);

        return s;
    }

    /**
     * Checks the parameter count against the intersection type to see if
     * this is a real overloading, or one that can trivially be disambiguated
     * by counting parameters.
     *
     * @param t
     * @param paramCount
     * @return
     */
    public static boolean actuallyOverloaded(IntersectionType t, int paramCount) {
        return matchingCount(t, paramCount) > 1;
    }

    /**
     * Returns the number of types in the intersection t whose domain have
     * paramCount args, except if paramCount is zero, in which case, it returns
     * zero, even if the true answer is one.
     * 
     * @param t
     * @param paramCount
     * @return
     */
    private static int matchingCount(IntersectionType t, int paramCount) {
        int sum = 0;
        List<Type> types = t.getElements();

        Type r = null;

        for (Type type : types) {
            Type r0;

            if (type instanceof ArrowType) {
                ArrowType at = (ArrowType) type;
                r0 = at.getDomain();
                if (r0 instanceof TupleType) {
                    TupleType tt = (TupleType) r0;
                    if (paramCount == tt.getElements().size())
                        sum++;
                } else if (paramCount == 1) {
                    // not a tuple means singleton
                    sum++;
                }
            } else if (type instanceof IntersectionType) {
                sum += matchingCount((IntersectionType) type, paramCount);
            } else {
                throw new CompilerError("Non arrowtype " + type + " in (function) intersection type");
            }
        }
        return sum;
    }

    /**
     * Returns the range derived from the an intersection type.
     * 
     * Note the implicit assumption that the paramCount is not zero;
     * that this is called for principal members of overload subsets, and
     * f() can never be a principal member of such a subset because it would
     * be a singleton.
     * 
     * 
     * @param t
     * @param paramCount GREATER THAN ZERO.
     * @param ta
     * @return
     */
    private static Type getRange(IntersectionType t, int paramCount, TypeAnalyzer ta) {
        List<Type> types = t.getElements();
        List<Type> typesToJoin = new ArrayList(types.size());

        for (Type type : types) {
            Type r0;

            if (type instanceof ArrowType) {
                ArrowType at = (ArrowType) type;

                // Ensure that the domain count matches the paramCount
                r0 = at.getDomain();
                if (r0 instanceof TupleType) {
                    TupleType tt = (TupleType) r0;
                    if (paramCount != tt.getElements().size())
                        continue;
                } else if (paramCount != 1) {
                    continue;
                }

                r0 = at.getRange();
            } else if (type instanceof IntersectionType) {
                r0 = getRange((IntersectionType) type, paramCount, ta);
            } else {
                throw new CompilerError("Non arrowtype " + type + " in (function) intersection type");
            }
            typesToJoin.add(r0);
        }
        return join(ta,typesToJoin);
    }

    /**
     * Gets the type of parameter i from the type derived for those members of the intersection
     * type with paramCount parameters, with help from the TypeAnalyzer ta.
     * 
     * @param t
     * @param i
     * @param paramCount
     * @param ta
     * @return
     */
    private static Type getParamType(IntersectionType t, int i, int paramCount, TypeAnalyzer ta) {
        List<Type> types = t.getElements();
        List<Type> typesToJoin = new ArrayList(types.size());

        for (Type type : types) {
            Type r0;

            r0 = getParamType(type, i, paramCount, ta);
            if (r0==null) continue;

            typesToJoin.add(normalizeSelfType(r0));
        }
        return join(ta,typesToJoin);
    }

    /**
     * Gets the type of parameter i from the type derived for those members of  
     * the type with paramCount parameters, with help from the TypeAnalyzer ta.
     * 
     * @param type
     * @param i  0 <= i < paramCount
     * @param paramCount > 0
     * @param ta
     * @return
     */
    public static Type getParamType(Type type, int i, int paramCount,
                                    TypeAnalyzer ta) {
        Type r0;
        if (type instanceof ArrowType) {
            ArrowType at = (ArrowType) type;
            r0 = at.getDomain();

            // Ensure that the domain count matches the paramCount
            if (r0 instanceof TupleType) {
                TupleType tt = (TupleType) r0;
                if (paramCount != tt.getElements().size())
                    r0 = null;
                else
                    r0 = tt.getElements().get(i);
            } else if (paramCount != 1) {
                r0 = null;
            }
        } else if (type instanceof IntersectionType) {
            r0 = getParamType((IntersectionType) type, i, paramCount, ta);
        } else {
            throw new CompilerError("Non arrowtype " + type + " in (function) intersection type");
        }
        return r0;
    }

    /**
     * @param ti
     * @return
     */
    private static Type normalizeSelfType(Type ti) {
        if (ti instanceof TraitSelfType)
            ti = ((TraitSelfType)ti).getNamed();
        return ti;
    }

    private static boolean tweakedSubtypeTest(TypeAnalyzer ta, Type sub_type, Type super_type ) {
        sub_type = normalizeSelfType(sub_type);
        super_type = normalizeSelfType(super_type);
        TypeSchemaAnalyzer tsa = new TypeSchemaAnalyzer(ta);
        return tsa.subtypeED(sub_type, super_type);
    }

    /**
     * @param paramCount
     * @return
     */
    private static String overloadedDomainSig(IntersectionType t, int paramCount, TypeAnalyzer ta) {
        StringBuilder buf = new StringBuilder();
        buf.append("(");
        for (int i = 0; i < paramCount; i++) {
            buf.append(NamingCzar.jvmTypeDesc(getParamType(t, i, paramCount, ta), null));
        }
        buf.append(")");
        return buf.toString();
    }

    private String overloadedDomainSig(int param_to_skip) {
        StringBuilder buf = new StringBuilder();
        buf.append("(");
        int i = 0;
        for (Type t : overloadedDomain()) {
            if (i != param_to_skip)
                buf.append(NamingCzar.jvmTypeDesc(t, ifNone));
            i++;
        }
        buf.append(")");
        return buf.toString();
   }

    protected List<Type> overloadedDomain() {
        List<Type> res = new ArrayList<Type>(paramCount);
        if (principalMember != null) {
            List<Param> params = principalMember.getParameters();
            List<StaticParam> sparams = principalMember.tagF.staticParameters();
            for (int i = 0; i < paramCount; i++) {
                Param p = params.get(i);
                res.add(sParamTaggedTypeFromParam(p, sparams));
                
            }
        } else  for (int i = 0; i < paramCount; i++) {      
            res.add(overloadedParamType(i));
        }       
        return res;
    }

    /**
     * @param p
     * @param sparams
     * @return
     */
    public Type sParamTaggedTypeFromParam(Param p, List<StaticParam> sparams) {
        return STypesUtil.insertStaticParams(
                normalizeSelfType((Type)p.getIdType().unwrap()),
                sparams);
    }

    private Type overloadedParamType(int param) {
        List<Type> typesToJoin = new ArrayList<Type>(lessSpecificThanSoFar.size());
        for (TaggedFunctionName f : lessSpecificThanSoFar) {
            List<Param> params = f.getParameters();
            List<StaticParam> sparams = f.tagF.staticParameters();
            Param p = params.get(param);
            if (! (p.getIdType().unwrap() instanceof Type))
                throw new CompilerError("Type is expected: " + p.getIdType().unwrap());
            typesToJoin.add(
                    sParamTaggedTypeFromParam(p, sparams));
        }
        return join(ta,typesToJoin);
    }

    /**
     * If there is a principal member (occurs in exported overloaded functions,
     * for example) then this could return more exceptions than necessary, for
     * example { IOException, FileNotFoundException }.
     * 
     * For local overloads, principal members need not exist, hence we need the
     * union for the general case.
     * @return
     */
    public String[] getExceptions() {
        HashSet<Type> exceptions = new HashSet<Type>();
        for (TaggedFunctionName f : lessSpecificThanSoFar) {
            List<Type> f_exceptions = f.getThrownTypes();
            exceptions.addAll(f_exceptions);
        }
        String[] string_exceptions = new String[exceptions.size()];
        int i = 0;
        for (Type e : exceptions) {
            string_exceptions[i++] = NamingCzar.jvmTypeDesc(e, ifNone, false, false);
        }
        return string_exceptions;
    }

    
    
    /**
     * Provides information about the static type and structure of the static 
     * type of a parameter to a function in an overload.
     * 
     * @author dr2chase
     */
    static class TypeStructure {
        /** If not generic, this is the Java type (boxed)
         * that the actual parameter must satisfy,
         * according to the variance.
         */
        final String fullname;
        
        /**
         * If generic, this is the stem; the stem must match, and then
         * the parameters must match appropriately.
         */
        final String rttiStem;
        
        /** static type parameters.
         *  IF NULL, this is a VarType node, to be stored in local localIndex
         *  when evaluated at runtime during dispatch.
         */
        final TypeStructure[] staticParameters;
        
        /** index of local to store corresponding type (VarType or generic)*/
        final int localIndex;
        
        /** next index after this node and all of its descendants. */
        final int successorIndex;
        
        /** What variance applies to this node?
         *  1 = co, 0 = in, -1 = contra
         */
        final int variance;
        public static final int COVARIANT = 1;
        public static final int INVARIANT = 0;
        public static final int CONTRAVARIANT = -1;
        
        
        final Set<String> variantGenericsContained;
        final Set<String> invariantGenericsContained;
        
        /**
         * If true, then the type in question is an object, and a faster type
         * check is possible.  This is NYI plumbing for an obvious and probably
         * helpful optimization.
         */
        final boolean isObject;
        
        public TypeStructure(String fullname, String stem, TypeStructure[] parameters,
                int localIndex, int successorIndex, int variance,
                Set<String> invariantGenericsContained, Set<String> variantGenericsContained, boolean isObject) {
            super();
            this.fullname = fullname;
            this.rttiStem = stem;
            this.staticParameters = parameters;
            this.localIndex = localIndex;
            this.successorIndex = successorIndex;
            this.variance = variance;
            this.invariantGenericsContained = invariantGenericsContained;
            this.variantGenericsContained = variantGenericsContained;
            this.isObject = isObject;
        }
        void emitInstanceOf(MethodVisitor mv, Label if_fail, boolean value_cast,
                            Set<String> top_level_invariants) {            
            /*
             *  If a static parameter is used anywhere in the signature
             *  in an invariant context, then its value is fixed to the
             *  result of static analysis, and runtime influence is superfluous.
             *  This thins the set of static parameters to those that are
             *  truly variant.
             *  
             *  The above is not true as example other_compiler_tests/IGO1.fss shows
             */
            
            if (invariantGenericsContained.size() == 0 && variantGenericsContained.size() == 0) {
                /* easy case, no inference */
                emitInstanceOfNG(mv, if_fail, value_cast); // helpful for debugging

            } else { // has generic
                
                if (value_cast) {
                    // convert value to its type.
                    // invokeinterface Any.getRTTI()
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, Naming.ANY_TYPE_CLASS, Naming.RTTI_GETTER, Naming.STATIC_PARAMETER_GETTER_SIG);
                }
                
                if (staticParameters == null) {
                    // VarType case -- leaf occurrence of static parameter
                    // TODO need to check bound on type against generic decl.
                    mv.visitVarInsn(Opcodes.ASTORE, localIndex);

                } else {
                    //RTTI on the top of the stack
                    String RTTIinterface = Naming.stemInterfaceToRTTIinterface(this.rttiStem); //might actually be a class in cases of Arrow and Tuple RTTIs
                    Label ahead = new Label();
                    
                    //DUP RTTI
                    mv.visitInsn(Opcodes.DUP);
                    
                    //check that the RTTI matches what we're looking for
                    mv.visitTypeInsn(Opcodes.INSTANCEOF, RTTIinterface);
                    
                    //if not, pop and try the next match
                    mv.visitJumpInsn(Opcodes.IFNE, ahead);
                    mv.visitInsn(Opcodes.POP);
                    mv.visitJumpInsn(Opcodes.GOTO, if_fail);
                    
                    //if so, store the RTTI for possible inferences
                    mv.visitLabel(ahead);
                    mv.visitTypeInsn(Opcodes.CHECKCAST, RTTIinterface);
                    mv.visitVarInsn(Opcodes.ASTORE, localIndex);
                    
                    //recursive check on static parameters
                    int i = Naming.STATIC_PARAMETER_ORIGIN;
                    for (TypeStructure p : staticParameters) {
                        mv.visitVarInsn(Opcodes.ALOAD, localIndex);
                        String method_name =
                            Naming.staticParameterGetterName(rttiStem, i);
                        if (RTTIinterface.endsWith(Naming.RTTI_INTERFACE_SUFFIX))
                            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, RTTIinterface, method_name, Naming.STATIC_PARAMETER_GETTER_SIG);
                        else // ends in RTTI_CLASS_SUFFIX - invoke virtual
                            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RTTIinterface, method_name, Naming.STATIC_PARAMETER_GETTER_SIG);
                        // get parameter
                        p.emitInstanceOf(mv, if_fail, false, top_level_invariants);
                        i++;
                    }
                }
            }
        }

        /**
         * @param mv
         * @param if_fail
         * @param value_cast
         */
        public void emitInstanceOfNG(MethodVisitor mv, Label if_fail,
                boolean value_cast) {
            if (value_cast) {
                InstantiatingClassloader.generalizedInstanceOf(mv, fullname);
                // Branch ahead if failure
            } else {
                
                // TOS is a Fortress RTTI
                // must check if the type extends the target type.
                // tricky cases are Arrow and Tuple.
                // getstatic fullname.RTTI
                // swap
                // invokevirtual RTTI.runtimeSupertypeOf
                mv.visitFieldInsn(Opcodes.GETSTATIC, fullname, Naming.RTTI_FIELD, Naming.RTTI_CONTAINER_DESC);
                mv.visitInsn(Opcodes.SWAP);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Naming.RTTI_CONTAINER_TYPE, Naming.RTTI_SUBTYPE_METHOD_NAME, Naming.RTTI_SUBTYPE_METHOD_SIG);
            }
            mv.visitJumpInsn(Opcodes.IFEQ, if_fail);
        }
    }
    
    /**
     * Returns a type decision structure for the given input type.
     * 
     * @param t the type to build the structure for.
     * @param spmap map recording occurrences of static parameter names. 
     *         Can be null for return types; these are not looked up at run time
     * @param variance context -- how must the type match?
     * @param storeAtIndex after the type test returns, store the result here.
     * @param staticParams the static parameters for this arm of the dispatch.
     * @return
     */
    TypeStructure makeTypeStructure(Type t, MultiMap<String, TypeStructure> spmap,
            int variance, int storeAtIndex,
            List<StaticParam> staticParams) {
        String fullname = NamingCzar.jvmBoxedTypeName(t, ifNone);
        String rttiStem = null;
        TypeStructure[] parameters = null;
        int[] variances = null;
        List<Type> type_elements = null;
        Set<String> invariantGenericsContained = Collections.<String>emptySet();
        Set<String> variantGenericsContained = Collections.<String>emptySet();
        boolean isVarType = false;
        boolean isObject = false;
        
        if (t instanceof TupleType) {
            type_elements = ((TupleType) t).getElements();
            int numStaticArgs = type_elements.size();
            variances = arrayOfInt(numStaticArgs, variance);
            rttiStem = Naming.TUPLE_RTTI_TAG + numStaticArgs;
            
        } else if (t instanceof ArrowType) {
            
            ArrowType at = ((ArrowType) t);
            Type range = at.getRange();
            Type domain = at.getDomain();
            if (domain instanceof TupleType) {
                type_elements = Useful.<Type,Type>list(((TupleType) domain).getElements(), range);
            } else {
                type_elements = Useful.<Type>list(domain, range);
            }
            int l = type_elements.size();
            variances = arrayOfInt(l, -variance);
            variances[l-1] = variance;
            rttiStem = Naming.ARROW_RTTI_TAG + l;
            
        } else if (t instanceof TraitSelfType) {
            return makeTypeStructure(((TraitSelfType)t).getNamed(), spmap,
                    variance, storeAtIndex, staticParams);
            
        } else if (t instanceof TraitType) {
            // Would love to inquire if this is an object type
            TraitType tt = (TraitType) t;
            List<StaticArg> tt_sa = tt.getArgs();
            Id tt_id = tt.getName();
            rttiStem = CodeGen.stemFromId(tt_id,packageAndClassName);
            if (tt_sa.size() > 0) {
                // process args into types. Non-type args will be somewhat problematic at first.
                type_elements = new ArrayList<Type>();
                // need to figure out how to normalize array length if non-type args.
                variances = arrayOfInt(tt_sa.size(), 0);
                int variance_index = 0;
                for (StaticArg sta : tt_sa) {
                    if (sta instanceof TypeArg) {
                        TypeArg sta_ta = (TypeArg) sta;
                        type_elements.add( sta_ta.getTypeArg() );
                        // if interesting variance, change it here.
                    } else {
                        // unhandled case.
                        throw new CompilerError("Only handling some static args of generic types");
                    }
                    variance_index++;
                }
            }

        } else if (t instanceof VarType) {
            VarType tt = (VarType) t;
            Id tt_id = tt.getName();
            rttiStem = tt_id.getText(); // DO NOT API-ANNOTATE!
            
            // If the overload arm has no static parameters, or if they
            // come from an enclosing scope (from a trait type, if this is
            // a method), then we do not care about them, they are effectively
            // type constants.
            
            if (staticParams != null) {
                for (StaticParam sp : staticParams) {
                    IdOrOp spn = sp.getName();
                    if (spn.getText().equals(rttiStem)) {
                        if (variance != 0) {
                            variantGenericsContained = Useful.set(tt_id.getText());
                        } else {
                            invariantGenericsContained = Useful.set(tt_id.getText());
                        }
                        isVarType = true;
                    }
                    break;
                }
            }

        } else if (t instanceof BottomType) {
            throw new CompilerError("Not handling Bottom type yet in generic overload dispatch");

        } else if (t instanceof AnyType) {
            // throw new CompilerError("Not handling Any type yet in generic overload dispatch");
            // might need to init "stem"
        } else if (t instanceof AbbreviatedType) {
            throw new CompilerError("Not handling Abbreviated type yet in generic overload dispatch");

        } else {
            throw new CompilerError("Not handling " + t.getClass() + " type yet in generic overload dispatch");
        }
        
        if (type_elements != null) {
            parameters = new TypeStructure[type_elements.size()];
            int i = 0;
            int next_index = storeAtIndex+1;
            HashSet<String> temp_var = new HashSet<String>();
            HashSet<String> temp_invar = new HashSet<String>();
            for (Type tt : type_elements) {
                TypeStructure ts = makeTypeStructure(tt, spmap, variances[i], next_index, staticParams);
                temp_var.addAll(ts.variantGenericsContained);
                temp_invar.addAll(ts.invariantGenericsContained);
                parameters[i++] = ts;
                next_index = ts.successorIndex;
            }
            // if no generics, then no sub-evaluation
            boolean hasGeneric = false;
            
            /* BECAUSE OF STATIC CHECKING, an invariant use of a static
             * parameter guarantees that we know the type -- no inference
             * will be necessary. (do we really believe this?) - NO WE DON"T - rip this out I think
             * 
             * Removing invariants from the set gives us an approximation of
             * what we will need to dynamically compute, or not.  Other context
             * may remove other things.
             */
            temp_var.removeAll(temp_invar);
            if (temp_var.size() > 0) {
                variantGenericsContained = temp_var;
                hasGeneric = true;
            }
            if (temp_invar.size() > 0) {
                invariantGenericsContained = temp_invar;
            }
            //if (! hasGeneric) - KBN - should not be reseting next index counter
            //   next_index = storeAtIndex+1;
            return new TypeStructure(fullname, rttiStem, parameters, storeAtIndex, next_index, variance, invariantGenericsContained, variantGenericsContained, isObject);
        } else if (isVarType) {
            TypeStructure x = new TypeStructure(fullname, rttiStem, parameters, storeAtIndex, storeAtIndex+1, variance, invariantGenericsContained, variantGenericsContained, isObject);
            if (spmap != null)
                spmap.putItem(rttiStem, x);
            return x;
        } else { 
            return new TypeStructure(fullname, rttiStem, parameters, storeAtIndex, storeAtIndex+1, variance, invariantGenericsContained, variantGenericsContained, isObject);
        }        
    }

    /**
     * @param size
     * @param initial
     */
    private int[] arrayOfInt(int size, int initial) {
        int[] variances = new int[size];
        Arrays.fill(variances, initial);
        return variances;
    }
    
    public void generateCall(MethodVisitor mv, int firstArgIndex, Label failLabel) {
        if (!splitDone) {
            throw new CompilerError("Must split overload set before generating call(s)");
        }
        int l = specificDispatchOrder.length;

        //  create type structures for parameter types.
        TypeStructure[][] type_structures = new TypeStructure[l][];
        MultiMap[] spmaps = new MultiMap[l];
        TypeStructure[] return_type_structures = new TypeStructure[l];
        
        for (int i = 0; i < l; i++) {
            TaggedFunctionName f = specificDispatchOrder[i];
            Functional eff = f.getF();
            List<Param> parameters = f.getParameters();
            MultiMap<String, TypeStructure> spmap = new MultiMap<String, TypeStructure>();
            spmaps[i] = spmap;
            List<StaticParam> staticParams = staticParametersOf(f.getF());

            Type rt = oa.getRangeType(eff);
            return_type_structures[i] = makeTypeStructure(rt,null, 1, 0, staticParams);

            // skip parameters -- no 'this' for ordinary functions
 
            if (parameters.size() == 1 && oa.getDomainType(eff) instanceof TupleType) {
                TupleType tt = (TupleType) oa.getDomainType(eff);
                List<Type> tl = tt.getElements();
                int storeAtIndex = tl.size();
                TypeStructure[] f_type_structures = new TypeStructure[storeAtIndex];
                type_structures[i] = f_type_structures;
                
                for (int j = 0; j < tl.size(); j++) {
                    Type t = STypesUtil.insertStaticParams(tl.get(j), tt.getInfo().getStaticParams());
                    TypeStructure type_structure = makeTypeStructure(t, spmap, 1, storeAtIndex, staticParams);
                    f_type_structures[j] = type_structure;
                    storeAtIndex = type_structure.successorIndex;
                }
                
            } else {
            
                int storeAtIndex = parameters.size();
                TypeStructure[] f_type_structures = new TypeStructure[storeAtIndex];
                type_structures[i] = f_type_structures;
    
                for (int j = 0; j < parameters.size(); j++) {
                    if (j != selfIndex()) {
                        Type t = oa.getParamType(eff,j);
                        TypeStructure type_structure = makeTypeStructure(t, spmap, 1, storeAtIndex, staticParams);
                        f_type_structures[j] = type_structure;
                        storeAtIndex = type_structure.successorIndex;
                    }
                }
            }
        }

        
        
        for (int i = 0; i < l; i++) {
            TaggedFunctionName f = specificDispatchOrder[i];
            TypeStructure[] f_type_structures = type_structures[i];                
            Label lookahead = null;
            boolean infer = false;

            List<StaticParam> staticParams = staticParametersOf(f.getF());
            
            if (i < l-1) {
                /* Trust the static checker; no need to verify
                 * applicability of the last one.
                 * Also, static parameters will be provided by static checker for the last one
                 */
                // Will need lookahead for the next one.
                lookahead = new Label();

                Set<String> top_level_invariants = new HashSet<String>();
                
                top_level_invariants.addAll(return_type_structures[i].invariantGenericsContained);
                
                for (int j = 0; j < f_type_structures.length; j++) {
                    if (j != selfIndex())
                        top_level_invariants.addAll(f_type_structures[j].invariantGenericsContained);
                }
                
                for (int j = 0; j < f_type_structures.length; j++) {
                    // Load actual parameter
                    if (j != selfIndex()) {
                        mv.visitVarInsn(Opcodes.ALOAD, j + firstArgIndex);
                        f_type_structures[j].emitInstanceOf(mv, lookahead, true,
                                            top_level_invariants);
                        if (f_type_structures[j].invariantGenericsContained.size() > 0 ||
                            f_type_structures[j].variantGenericsContained.size() > 0)
                                infer = true;
                    }
                }
            }
            
            //Runtime inference for some cases
            if (infer) {
                @SuppressWarnings("unchecked")
                MultiMap<String,TypeStructure> staticTss = spmaps[i];
                
                //load template name
                String arrow = NamingCzar.makeArrowDescriptor(f.getParameters(), f.getReturnType(),f.tagA);
                String owner = Naming.dotToSep(f.tagA.getText()) + Naming.GEAR + "$" + f.tagF.name().getText() +
                                Naming.LEFT_OXFORD + Naming.RIGHT_OXFORD + Naming.ENVELOPE + "$" + Naming.HEAVY_X + arrow;
                
                mv.visitLdcInsn(owner);
                
                //infer and load RTTIs
                for (StaticParam sp : staticParams) {
                    Set<TypeStructure> instances = staticTss.get(sp.getName().getText());
                    if (instances.size() > 1) {
                        
                        //sort static parameters by their variance and put into
                        //arrays using their local variable number
                        List<Integer> invariantInstances = new ArrayList<Integer>();
                        List<Integer> covariantInstances = new ArrayList<Integer>();
                        List<Integer> contravariantInstances = new ArrayList<Integer>();
                        for (TypeStructure ts: instances) {
                            switch (ts.variance) {
                            case TypeStructure.INVARIANT:
                                  invariantInstances.add(ts.localIndex);
                                  break;
                            case TypeStructure.CONTRAVARIANT:
                                  contravariantInstances.add(ts.localIndex);
                                  break;
                            case TypeStructure.COVARIANT:
                                  covariantInstances.add(ts.localIndex);
                                  break;
                            default:
                                throw new CompilerError("Unexpected Variance on TypeStructure during " +
                                		"generic instantiation analysis for overload dispatch");
                            }
                        }
                        
                        // if any invariant instances, we must use that RTTI and check that 
                        //1) any other invariant instances are the same type (each subtypes the other)
                        //2) any covariant instances are subtypes of the invariant instance
                        //3) any contravariant instances are supertypes of the invariant instance
                        if (invariantInstances.size() > 0) {
                            Label popLabel = new Label();
                            Label proceedLabel = new Label();
                            
                            //a valid instantiation must use the runtime type
                            //of all invariant instances (which must all be the same)
                            //thus, wlog, we can use the first invariant instance
                            int RTTItoUse = invariantInstances.get(0); 
                            
                            //1) for each other invariant instance, they must be the same
                            //which we test by checking that each subtypes the other
                            for (int k = 1; k < invariantInstances.size(); k++) {
                                int RTTIcompare = invariantInstances.get(k);
                                //RTTItoUse.runtimeSupertypeOf(RTTIcompare)
                                mv.visitVarInsn(Opcodes.ALOAD,RTTItoUse);
                                mv.visitVarInsn(Opcodes.ALOAD, RTTIcompare);
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Naming.RTTI_CONTAINER_TYPE, Naming.RTTI_SUBTYPE_METHOD_NAME, Naming.RTTI_SUBTYPE_METHOD_SIG);
                                mv.visitJumpInsn(Opcodes.IFEQ, popLabel);
                                //RTTIcompare.runtimeSupertypeOf(RTTItoUse)
                                mv.visitVarInsn(Opcodes.ALOAD, RTTIcompare);
                                mv.visitVarInsn(Opcodes.ALOAD, RTTItoUse);
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Naming.RTTI_CONTAINER_TYPE, Naming.RTTI_SUBTYPE_METHOD_NAME, Naming.RTTI_SUBTYPE_METHOD_SIG);
                                mv.visitJumpInsn(Opcodes.IFEQ, popLabel);
                            }
                            
                            //2) for each covariant instance, the runtime type (RTTIcompare) must be a
                            // subtype of the instantiated type (RTTItoUse)
                            for (int RTTIcompare : covariantInstances) {
                                //RTTItoUse.runtimeSupertypeOf(RTTIcompare)
                                mv.visitVarInsn(Opcodes.ALOAD,RTTItoUse);
                                mv.visitVarInsn(Opcodes.ALOAD, RTTIcompare);
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Naming.RTTI_CONTAINER_TYPE, Naming.RTTI_SUBTYPE_METHOD_NAME, Naming.RTTI_SUBTYPE_METHOD_SIG);
                                mv.visitJumpInsn(Opcodes.IFEQ, popLabel);
                            }
                            
                            //3) for each contravariant instances, the instantiated type (RTTItoUse) must be a 
                            // subtype of the runtime type (RTTIcompare)
                            for (int RTTIcompare : contravariantInstances) {
                                //RTTIcompare.runtimeSupertypeOf(RTTItoUse)
                                mv.visitVarInsn(Opcodes.ALOAD, RTTIcompare);
                                mv.visitVarInsn(Opcodes.ALOAD,RTTItoUse);
                                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Naming.RTTI_CONTAINER_TYPE, Naming.RTTI_SUBTYPE_METHOD_NAME, Naming.RTTI_SUBTYPE_METHOD_SIG);
                                mv.visitJumpInsn(Opcodes.IFEQ, popLabel);
                            }
                            
                            //checks out, so load the RTTI we will use
                            mv.visitVarInsn(Opcodes.ALOAD,RTTItoUse);
                            mv.visitJumpInsn(Opcodes.GOTO, proceedLabel);
                            
                            //if we failed pop from top of statck and try the next possible function definition
                            mv.visitLabel(popLabel);
                            mv.visitInsn(Opcodes.POP);
                            mv.visitJumpInsn(Opcodes.GOTO, lookahead);
                            
                            //success - call this function
                            mv.visitLabel(proceedLabel);
                            
                        } else { //otherwise, we might need to do inference which is not implemented yet
                            throw new CompilerError("non-invariant inference for multiple instances of static parameters not implemented");
                        }
                    } else if (instances.size() == 1) { //easy case
                        int offset = ((TypeStructure) instances.toArray()[0]).localIndex;
                        mv.visitVarInsn(Opcodes.ALOAD,offset);
                    }
                }
                
                //load the function
                String ic_sig = InstantiatingClassloader.jvmSignatureForOnePlusNTypes(NamingCzar.internalString, staticParams.size(), 
                        Naming.RTTI_CONTAINER_TYPE, 
                        Naming.internalToDesc(NamingCzar.internalObject));
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, Naming.RT_HELPERS, "loadClosureClass", ic_sig);
                
                //cast to object arrow
                int numParams = f.getParameters().size();
                String objectAbstractArrow = objectAbstractArrowTypeForNParams(numParams);
                InstantiatingClassloader.generalizedCastTo(mv, objectAbstractArrow);
                
                
                //load parameters
                for (int j = 0; j < f_type_structures.length; j++) {
                    // Load actual parameter
                    if (j != selfIndex()) {
                        mv.visitVarInsn(Opcodes.ALOAD, j + firstArgIndex);
                        //no cast needed here - done by apply method
                    }
                }
                
                //call apply method
                String objectArrow = objectArrowTypeForNParams(numParams);
                String applySig = InstantiatingClassloader.jvmSignatureForNTypes(numParams, NamingCzar.internalObject, Naming.internalToDesc(NamingCzar.internalObject));
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, objectArrow, Naming.APPLY_METHOD, applySig);
                
                //cast to correct return type
                String returnType = NamingCzar.makeBoxedTypeName(f.getReturnType(),f.tagA);
                InstantiatingClassloader.generalizedCastTo(mv, returnType);
            } else {
            
                //no inferences needed
                loadThisForMethods(mv);
    
                for (int j = 0; j < f_type_structures.length; j++) {
                    // Load actual parameter
                    if (j != selfIndex()) {
                        mv.visitVarInsn(Opcodes.ALOAD, j + firstArgIndex);
                        InstantiatingClassloader.generalizedCastTo(mv, f_type_structures[j].fullname);
                    }
                }
           
                String sig = jvmSignatureFor(f);

                invokeParticularMethod(mv, f, sig);
            }
            
            mv.visitInsn(Opcodes.ARETURN);

            if (lookahead != null)
                mv.visitLabel(lookahead);
        }
    }

    private String objectAbstractArrowTypeForNParams(int numParams) {
        StringBuilder ret = new StringBuilder("AbstractArrow" + Naming.LEFT_OXFORD);
        for (int i = 0; i < numParams; i++) ret.append(NamingCzar.internalObject + ";"); // params
        ret.append(NamingCzar.internalObject + Naming.RIGHT_OXFORD); // return
        return ret.toString();
    }
    
    private String objectArrowTypeForNParams(int numParams) {
        StringBuilder ret = new StringBuilder("Arrow" + Naming.LEFT_OXFORD);
        for (int i = 0; i < numParams; i++) ret.append(NamingCzar.internalObject + ";"); // params
        ret.append(NamingCzar.internalObject + Naming.RIGHT_OXFORD); // return
        return ret.toString();
    }
    
    protected void loadThisForMethods(MethodVisitor mv) {
        // default does nothing
    }
    
    

    /**
     * Invoke f (it's a forwarding call) with casts inserted as necessary.
     * 
     * @param mv
     * @param firstArgIndex
     * @param f
     */
    private void generateLeafCall(MethodVisitor mv, int firstArgIndex,
            TaggedFunctionName f) {
        String sig = jvmSignatureFor(f);

        int i = firstArgIndex;
        List<Param> params = f.getParameters();
        
        for (Param p : params) {
            mv.visitVarInsn(Opcodes.ALOAD, i);

            TypeOrPattern ty = p.getIdType().unwrap();
            if (! (ty instanceof Type))
                throw new CompilerError("Type is expected: " + ty);
            InstantiatingClassloader.generalizedCastTo(mv,  NamingCzar.jvmBoxedTypeName((Type)ty, ifNone));
            // mv.visitTypeInsn(Opcodes.CHECKCAST, NamingCzar.jvmBoxedTypeDesc((Type)ty, ifNone));
            i++;
        }
        if (CodeGenerationPhase.debugOverloading)
            System.err.println("Emitting call " + f.tagF + sig);

        invokeParticularMethod(mv, f, sig);
        mv.visitInsn(Opcodes.ARETURN);
    }

    /**
     * @param f
     * @return
     */
    String jvmSignatureFor(TaggedFunctionName f) {
        return NamingCzar.jvmSignatureFor(f.tagF, f.tagA); 
    }

    /**
     * @param fu
     * @return
     */
    private static List<StaticParam> staticParametersOf(Functional fu) {
        List<StaticParam> params = null;
        if (fu instanceof FunctionalMethod) {
            List<StaticParam> ltsp = ((FunctionalMethod) fu).traitStaticParameters();
            List<StaticParam> lfsp = ((FunctionalMethod) fu).staticParameters();
            List<StaticParam> lsp = Useful.concat(ltsp, lfsp);
            if (lsp.size() > 0)
                params = lsp;
        } else if (fu instanceof DeclaredFunction) {
            DeclaredFunction df = (DeclaredFunction) fu;
            List<StaticParam> lsp = df.staticParameters();
            if (lsp.size() > 0)
                params = lsp;
        } else if (fu instanceof DeclaredMethod) {
            List<StaticParam> lsp = fu.staticParameters();
            if (lsp.size() > 0)
                params = lsp;
        } else if (fu instanceof FieldGetterOrSetterMethod) {
            List<StaticParam> lsp = fu.staticParameters();
            if (lsp.size() > 0)
                params = lsp;
        } else if (fu instanceof Constructor) {
            throw new CompilerError("Unimplemented arm of jvm signature " + fu);
        } else {
            throw new CompilerError("Unexpected subtype of FunctionalMethod " + fu);
        }
        return params;
    }


    public String toString() {
        if (lessSpecificThanSoFar.size() == 1) {
            return lessSpecificThanSoFar.iterator().next().toString();
        }
        if (splitDone) {
            return toStringR("");
        } else {
            return lessSpecificThanSoFar.toString();
        }
    }

    private String toStringR(String indent) {
        {
            String s = indent;
            int l = specificDispatchOrder.length;
            for (int i = 0; i < l; i++) {
                TaggedFunctionName f = specificDispatchOrder[i];
                s += f.toString() + "\n";
            }
            return s;
        }
    }

    /**
     * Overloaded functions get a slightly mangled name.
     *
     * @param name
     * @return
     */
    public static String oMangle(String name) {
        return name; // no mangling after all.
    }

    public void generateAnOverloadDefinition(String _name, CodeGenClassWriter cv) {
        // System.err.println("Generating "+ name + "\n" +
        //                    "    principalMember "+ principalMember + "\n" + this.toStringR("   "));
        String filtered_name = chooseName(_name, NodeUtil.nameSuffixString(name));
        generateAnOverloadDefinitionInner(filtered_name, cv);

        for (Map.Entry<String, OverloadSet> o_entry : getOverloadSubsets().entrySet()) {
            String ss = o_entry.getKey();
            OverloadSet sos = o_entry.getValue();
            if (sos != this) {
                sos.generateAnOverloadDefinitionInner(filtered_name, cv);
            }
        }
    }

    protected String chooseName(String callers_name,
            String nameSuffixString) {
        return callers_name;
    }

    protected void generateAnOverloadDefinitionInner(String _name, CodeGenClassWriter cv) {

        // "(" anOverloadedArg^N ")" returnType
        // Not sure what to do with return type.
        String signature = getSignature();
        String[] exceptions = getExceptions();

        // Right now we extract any necessary static arguments from
        // the principalMember.  This may turn out to be wrong, but it
        // ought to be the case that principalMember is always set to
        // the statically most applicable method for the given set of
        // overloadings under consideration.  [At present this does
        // not always appear to be the case in practice.]
        List<StaticParam> sargs = null;
        /* Some overloads have static args, but even if they are supplied,
         * runtime inference is still necessary (so I think) -- drc 2010-02-24
         * 
         * Not quite so -- if each static parameter is mentioned in any
         * invariant occurrence, then the supplied type is exact.
         * Runtime inference is only necessary when the occurrences are
         * ALL in variant context.  Static analysis can also supply 
         * no-more-precise-than information that it learns from context.
         * If no-less and no-more precise information are equal, then
         * again, dynamic inference is not needed.
         */
        if (principalMember != null) {
            sargs = staticParametersOf(principalMember.tagF);
        }

        if (CodeGenerationPhase.debugOverloading)
            System.err.println("Emitting overload " + _name + signature);

        String PCNOuter = null;
        Naming.XlationData xldata = null; 
        String overloaded_name = oMangle(_name);
        
        ArrayList<InitializedStaticField> isf_list = new ArrayList<InitializedStaticField>();
        
        if (sargs != null) {
            // Map<String, String> xlation = new HashMap<String, String>();
            xldata = CodeGen.xlationData(Naming.FUNCTION_GENERIC_TAG);
                        
            String sparamsType = NamingCzar.genericDecoration(sargs, xldata, ifNone);
            // TODO: which signature is which?  One needs to not have generics info in it.
            genericSchema =
                NamingCzar.makeArrowDescriptor(ifNone, overloadedDomain(), getRange());
            
            /* Save this for later, to forestall collisions with
             * single functions that hit the generic type.
             * 
             * Question: is this a problem with references to single
             * types within the overload itself?  I think it might be, if we
             * do not use exactly the same handshake.
             */
            // temporary fix to problems with type analysis.  Check to make sure
            // that we don't generate a generic function class that was already
            // generated by our parent
            // A more complete fix would delve into the type checker
            // see bug PROJECTFORTRESS-19 (not_working_library_tests/MaybeTest2.fss)
            if (parent != null && parent.genericSchema.equals(genericSchema)) return; //prevent duplication
            
            String packageAndClassName = NamingCzar.javaPackageClassForApi(ifNone);
            // If we have static arguments, then our caller must be
            // invoking us by instantiating a closure class and then
            // calling its apply method.  Thus we need to make sure
            // that we generate the expected closure class rather than
            // a top-level method.
            String PCN =
                Naming.genericFunctionPkgClass(packageAndClassName, _name,
                                                   sparamsType, genericSchema);
            PCNOuter =
                Naming.genericFunctionPkgClass(packageAndClassName, _name,
                                                   Naming.LEFT_OXFORD + Naming.RIGHT_OXFORD,
                                                   genericSchema);
            // System.err.println("Looks generic.\n    signature " + signature +
            //                    "\n    gArrType " + genericArrowType +
            //                    "\n    sparamsType " + sparamsType +
            //                    "\n    PCN " + PCN +
            //                    "\n    PCNOuter " + PCNOuter);
            cv = new CodeGenClassWriter(ClassWriter.COMPUTE_FRAMES, cv);
            
            overloaded_name = InstantiatingClassloader.closureClassPrefix(PCN, cv, PCN, signature, null, isf_list);
        }
        MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC
                    + Opcodes.ACC_STATIC, // access,
                    overloaded_name, // name,
                    signature, // sp.getFortressifiedSignature(),
                    null, // signature, // depends on generics, I think
                    exceptions); // exceptions);
        
        generateBody(mv);
        
        if (PCNOuter != null) {
            InstantiatingClassloader.optionalStaticsAndClassInitForTO(isf_list, cv);
            cv.dumpClass(PCNOuter, xldata);
        }
    }

    protected void generateBody(MethodVisitor mv) {
        mv.visitCode();
        Label fail = new Label();

        generateCall(mv, firstArg(), fail); // Guts of overloaded method

        // Emit failure case
        mv.visitLabel(fail);
        // Boilerplate for throwing an error.
        // mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                           NamingCzar.miscCodegen, NamingCzar.matchFailure, NamingCzar.errorReturn);
        mv.visitInsn(Opcodes.ATHROW);

        mv.visitMaxs(getParamCount(), getParamCount()); // autocomputed
        mv.visitEnd();
    }


    public BATree<String, OverloadSet> getOverloadSubsets() {
        return overloadSubsets;
    }
    
    protected int firstArg() {
        return 0;
    }

    static public class Local extends AmongApis {
        public Local(final APIName apiname, IdOrOpOrAnonymousName name, TypeAnalyzer ta, Set<Functional> defs, int n) {
            super(apiname, name, ta, Useful.applyToAll(defs, new F<Functional, TaggedFunctionName>() {

                @Override
                public TaggedFunctionName apply(Functional f) {
                    return new TaggedFunctionName(apiname, f);
                }
            }), n);
        }

    }

    static public abstract class Factory {
        abstract public OverloadSet make(final APIName apiname, IdOrOpOrAnonymousName name, TypeAnalyzer ta, Set<Functional> defs, int n);
    }
    
    static public final Factory localFactory = new Factory() {

        @Override
        public OverloadSet make(APIName apiname, IdOrOpOrAnonymousName name,
                TypeAnalyzer ta, Set<Functional> defs, int n) {
            return new Local(apiname, name, ta, defs, n);
        }
        
    };
    
    static public class  TraitOrObjectFactory extends Factory {
        
        final int invokeOpcode;
        final Naming.ClassNameBundle cnb;
        
        public TraitOrObjectFactory(int invoke_opcode, Naming.ClassNameBundle cnb) {
            this.invokeOpcode = invoke_opcode;
            this.cnb = cnb;
        }

        @Override
        public OverloadSet make(APIName apiname, IdOrOpOrAnonymousName name,
                TypeAnalyzer ta, Set<Functional> defs, int n) {
            
            Functional one_func = defs.iterator().next();
            int self_index = Naming.NO_SELF;
            if (one_func instanceof FunctionalMethod) {
                self_index = ((FunctionalMethod) one_func).selfPosition();
                String new_name = Naming.fmDottedName(NodeUtil.nameSuffixString(name), self_index);
                if (name instanceof Id)
                    name = NodeFactory.makeId((Id)name, new_name);
                else if (name instanceof Op)
                    name = NodeFactory.makeOp((Op)name, new_name);
                else 
                    throw new CompilerError("Was not expecting an overloaded anonymous name.");

            }
            
            return new ForTraitOrObject(apiname, name, ta, defs, null, n, self_index, cnb, invokeOpcode);
        }
        
    };
    
    // TODO if it is a functional method, need to whack the name, too.
    static public class ForTraitOrObject extends AmongApis {
        /**
         * Indicates, in the Functional representation of the methods,
         * which parameter will not be present.
         */
        final int selfIndex;
        final int invokeOpcode;
        final Naming.ClassNameBundle cnb;
        
        ForTraitOrObject(
                final APIName apiname, IdOrOpOrAnonymousName name,
                TypeAnalyzer ta, Set<Functional> defs, OverloadSet parent, int n,
                int self_index, Naming.ClassNameBundle cnb, int invoke_opcode) {
            super(apiname, name, ta, Useful.applyToAll(defs, new F<Functional, TaggedFunctionName>() {

                @Override
                public TaggedFunctionName apply(Functional f) {
                    return new TaggedFunctionName(apiname, f);
                }
            }), n);
            
            selfIndex = self_index;
            this.cnb = cnb;
            this.invokeOpcode = invoke_opcode;
        }
        
        ForTraitOrObject(APIName ifNone,
                IdOrOpOrAnonymousName name,
                TypeAnalyzer ta,
                Set<TaggedFunctionName> childLSTSF,
                OverloadSet parent, int paramCount,
                boolean this_disambiguates_the_erasure,
                int self_index, Naming.ClassNameBundle cnb, int invoke_opcode) {
            super(ifNone, name, ta, childLSTSF, paramCount);
            selfIndex = self_index;
            this.cnb = cnb;
            this.invokeOpcode = invoke_opcode;
        }

        protected OverloadSet makeSubset(Set<TaggedFunctionName> childLSTSF, TaggedFunctionName _principalMember, OverloadSet parent) {
            OverloadSet subset = new ForTraitOrObject(ifNone, name, ta, childLSTSF, parent, paramCount, true, selfIndex, cnb, invokeOpcode);
            subset.principalMember = _principalMember;
            return subset;
        }

        protected void loadThisForMethods(MethodVisitor mv) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
        }

        protected String chooseName(String callers_name,
                String nameSuffixString) {
            return nameSuffixString;
        }

        protected int firstArg() {
            return selfIndex() == Naming.NO_SELF ? 1 : 0;
        }

        protected int selfIndex() {
            return selfIndex;
        }

        /**
         * Emit the invocation for a particular type of overloaded functions.
         *
         * @param mv
         * @param f
         * @param sig
         */
        protected void invokeParticularMethod(MethodVisitor mv, TaggedFunctionName f,
                                              String sig) {
            List<StaticParam> sargs = staticParametersOf(f.tagF);
            String sparamsType = "";
            String genericArrowType = "";
            //String ownerName = NamingCzar.apiAndMethodToMethodOwner(f.tagA, f.tagF);
            String mname = NodeUtil.nameSuffixString(name);
            // NamingCzar.apiAndMethodToMethod(f.tagA, f.tagF);

            // this ought to work better here.
            // Why is this not "mname" instead of name.stringName?
            if (getOverloadSubsets().containsKey(mname+sig)) {
                mname = NamingCzar.mangleAwayFromOverload(mname);
            }
            
            if (sargs != null) {
                 genericArrowType =
                    NamingCzar.makeArrowDescriptor(ifNone, oa.getDomainType(f.tagF), oa.getRangeType(f.tagF));
                 sparamsType = NamingCzar.genericDecoration(sargs, null, ifNone);
//                ownerName =
//                    Naming.genericFunctionPkgClass(ownerName, mname,
//                                                       sparamsType, genericArrowType);
                 mname = Naming.APPLIED_METHOD;
            }
            mv.visitMethodInsn(invokeOpcode, cnb.className, mname, sig);
        }
        
        String jvmSignatureFor(TaggedFunctionName f) {
            // TODO need to skip selfIndex in f.
            return NamingCzar.jvmSignatureFor(f.tagF, selfIndex, f.tagA); 
        }
        
       /*
         * See super implementation for comparison and comments.
         * 
         * @param _name
         * @param cv
         */
        
        protected void generateAnOverloadDefinitionInner(String _name, CodeGenClassWriter cv) {

            // "(" anOverloadedArg^N ")" returnType
            // Not sure what to do with return type.
            String signature = getSignature(selfIndex);
            String[] exceptions = getExceptions();

          
            List<StaticParam> sargs = null;
            
            if (principalMember != null) {
                sargs = staticParametersOf(principalMember.tagF);
            }
            
            if (sargs != null) {
                // Not yet prepared for overloaded GENERIC methods!
                throw new CompilerError("Not yet ready for overloaded generic methods:\n" + this);

            }

            if (CodeGenerationPhase.debugOverloading)
                System.err.println("Emitting overloaded method " + _name + signature);

            String PCNOuter = null;
            Naming.XlationData xldata = null; 
            String overloaded_name = oMangle(_name);
            
            ArrayList<InitializedStaticField> isf_list = new ArrayList<InitializedStaticField>();
            
            MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC, // access,
                        overloaded_name, // name,
                        signature, // sp.getFortressifiedSignature(),
                        null, // signature, // depends on generics, I think
                        exceptions); // exceptions);
            
            generateBody(mv);
            
            if (PCNOuter != null) {
                InstantiatingClassloader.optionalStaticsAndClassInitForTO(isf_list, cv);
                cv.dumpClass(PCNOuter, xldata);
            }
        }
    }

    static public class AmongApis extends OverloadSet {
        /**
         * Emit the invocation for a particular type of overloaded functions.
         *
         * @param mv
         * @param f
         * @param sig
         */
        protected void invokeParticularMethod(MethodVisitor mv, TaggedFunctionName f,
                                              String sig) {
            List<StaticParam> sargs = staticParametersOf(f.tagF);
            String ownerName = NamingCzar.apiAndMethodToMethodOwner(f.tagA, f.tagF);
            String mname = NamingCzar.apiAndMethodToMethod(f.tagA, f.tagF);

            // this ought to work better here.
            
            if (sargs != null) {
                String trial_owner_name = closureClassNameForGenericOverloadArm(f, sargs,
                        ownerName, mname);
                if (getOverloadSubsets().containsKey(trial_owner_name)) {
                    trial_owner_name = closureClassNameForGenericOverloadArm(f, sargs,
                            ownerName, NamingCzar.mangleAwayFromOverload(mname));
                }
                mname = Naming.APPLIED_METHOD;
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, trial_owner_name, mname, sig);
            } else {
                if (getOverloadSubsets().containsKey(name.stringName()+sig)) {
                    mname = NamingCzar.mangleAwayFromOverload(mname);
                }
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, ownerName, mname, sig);
                
            }
        }

        /**
         * @param f
         * @param sargs
         * @param ownerName
         * @param mname
         * @return
         */
        public String closureClassNameForGenericOverloadArm(
                TaggedFunctionName f, List<StaticParam> sargs,
                String ownerName, String mname) {
            String genericArrowType = 
                NamingCzar.makeArrowDescriptor(ifNone, oa.getDomainType(f.tagF), oa.getRangeType(f.tagF));
            String sparamsType = NamingCzar.genericDecoration(sargs, null, ifNone);
            ownerName =
                Naming.genericFunctionPkgClass(ownerName, mname,
                                                   sparamsType, genericArrowType);
            return ownerName;
        }

        /* Boilerplate follows, because this is a subtype. */

        protected AmongApis(APIName ifNone, IdOrOpOrAnonymousName name, TypeAnalyzer ta, OverloadingOracle oa, 
                            Set<TaggedFunctionName> lessSpecificThanSoFar,
                            OverloadSet parent, int paramCount) {
            super(ifNone, name, ta, oa, lessSpecificThanSoFar, parent, paramCount);
        }

        public AmongApis(APIName ifNone, IdOrOpOrAnonymousName name, TypeAnalyzer ta, Set<TaggedFunctionName> defs, int n) {
            super(name, ta, new OverloadingOracle(ta), defs, n, ifNone);
        }
        
        protected AmongApis(APIName ifNone, IdOrOpOrAnonymousName name, TypeAnalyzer ta, Set<TaggedFunctionName> defs, OverloadSet parent, int n) {
            super(ifNone, name, ta, new OverloadingOracle(ta), defs, parent, n);
        }

		protected OverloadSet makeSubset(Set<TaggedFunctionName> childLSTSF, TaggedFunctionName _principalMember, OverloadSet parent) {
            OverloadSet subset = new AmongApis(ifNone, name, ta, new OverloadingOracle(ta), childLSTSF, parent, paramCount);
            subset.principalMember = _principalMember;
            return subset;
        }

        public int hashCode() {
            return super.hashCode();
        }

        public boolean equals(Object o) {
            if (o == null) return false;
            if ((o.getClass() != this.getClass()) || (o.hashCode() != this.hashCode())) {
                return false;
            }
            else {
                return super.equals(o);
            }
        }

   }

    public boolean notGeneric() {
        for (TaggedFunctionName tfn: lessSpecificThanSoFar) {
            Functional f = tfn.getF();
            List<StaticParam> lsp = staticParametersOf(f);
            if (lsp.size() > 0)
                return false;
            
        }
        return true;
    }

}
