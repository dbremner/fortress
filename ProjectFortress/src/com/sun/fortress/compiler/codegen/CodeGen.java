/*******************************************************************************
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
******************************************************************************/
package com.sun.fortress.compiler.codegen;

import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.jar.JarOutputStream;

import org.objectweb.asm.*;
import org.objectweb.asm.Label;
import org.objectweb.asm.util.*;

import edu.rice.cs.plt.collect.CollectUtil;
import edu.rice.cs.plt.collect.PredicateSet;
import edu.rice.cs.plt.collect.Relation;
import edu.rice.cs.plt.collect.IndexedRelation;
import edu.rice.cs.plt.tuple.Option;
import edu.rice.cs.plt.tuple.Pair;

import com.sun.fortress.compiler.AnalyzeResult;
import com.sun.fortress.compiler.GlobalEnvironment;
import com.sun.fortress.compiler.NamingCzar;
import com.sun.fortress.compiler.WellKnownNames;
import com.sun.fortress.compiler.index.ApiIndex;
import com.sun.fortress.compiler.index.ComponentIndex;
import com.sun.fortress.compiler.index.Function;
import com.sun.fortress.compiler.index.Functional;
import com.sun.fortress.compiler.index.FunctionalMethod;
import com.sun.fortress.compiler.index.HasSelfType;
import com.sun.fortress.compiler.index.TypeConsIndex;
import com.sun.fortress.compiler.OverloadSet;
import com.sun.fortress.compiler.typechecker.TypeAnalyzer;
import com.sun.fortress.compiler.typechecker.StaticTypeReplacer;
import com.sun.fortress.exceptions.CompilerError;
import com.sun.fortress.nodes.*;
import com.sun.fortress.nodes.Type;
import com.sun.fortress.nodes_util.*;
import com.sun.fortress.runtimeSystem.BAlongTree;
import com.sun.fortress.runtimeSystem.InstantiatingClassloader;
import com.sun.fortress.runtimeSystem.Naming;
import com.sun.fortress.syntax_abstractions.ParserMaker.Mangler;
import com.sun.fortress.useful.BA2Tree;
import com.sun.fortress.useful.BASet;
import com.sun.fortress.useful.BATree;
import com.sun.fortress.useful.Debug;
import com.sun.fortress.useful.DefaultComparator;
import com.sun.fortress.useful.DeletedList;
import com.sun.fortress.useful.Fn;
import com.sun.fortress.useful.InsertedList;
import com.sun.fortress.useful.MagicNumbers;
import com.sun.fortress.useful.MultiMap;
import com.sun.fortress.useful.StringHashComparer;
import com.sun.fortress.useful.TopSort;
import com.sun.fortress.useful.TopSortItemImpl;
import com.sun.fortress.useful.Useful;

import com.sun.fortress.scala_src.useful.STypesUtil;

// Note we have a name clash with org.objectweb.asm.Type
// and com.sun.fortress.nodes.Type.  If anyone has a better
// solution than writing out their entire types, please
// shout out.
public class CodeGen extends NodeAbstractVisitor_void implements Opcodes {

    CodeGenClassWriter cw;
    CodeGenMethodVisitor mv; // Is this a mistake?  We seem to use it to pass state to methods/visitors.
    final String packageAndClassName;
    private String traitOrObjectName; // set to name of current trait or object, as necessary.
    private String springBoardClass; // set to name of trait default methods class, if we are emitting it.

    // traitsAndObjects appears to be dead code.
    // private final Map<String, ClassWriter> traitsAndObjects =
    //     new BATree<String, ClassWriter>(DefaultComparator.normal());
    private final TypeAnalyzer ta;
    private final ParallelismAnalyzer pa;
    private final FreeVariables fv;
    private final Map<IdOrOpOrAnonymousName, MultiMap<Integer, Function>> topLevelOverloads;
    private final MultiMap<String, Function> exportedToUnambiguous;
    private Set<String> overloadedNamesAndSigs;

    // lexEnv does not include the top level or object right now, just
    // args and local vars.  Object fields should have been translated
    // to dotted notation at this point, right?  Right?  (No, not.)
    private BATree<String, VarCodeGen> lexEnv;
    boolean inATrait = false;
    boolean inAnObject = false;
    boolean inABlock = false;
    private boolean emittingFunctionalMethodWrappers = false;
    private TraitObjectDecl currentTraitObjectDecl = null;

    private boolean fnRefIsApply = false; // FnRef is either apply or closure

    final Component component;
    private final ComponentIndex ci;
    private GlobalEnvironment env;

    private static final int NO_SELF = -1;
    
    abstract static class InitializedStaticField {
        abstract public void forClinit(MethodVisitor mv);

        abstract public String asmName();

        abstract public String asmSignature();
    }

    /**
     * Some traits and objects end up with initialized static fields as part of
     * their implementation.  They accumulate here, and their initialization
     * is packed into the clinit method.
     * 
     * Null if not in a trait or object scope.
     */
    private List<InitializedStaticField> initializedStaticFields_TO;
    
    // Create a fresh codegen object for a nested scope.  Technically,
    // we ought to be able to get by with a single lexEnv, because
    // variables ought to be unique by location etc.  But in practice
    // I'm not assuming we have a unique handle for any variable,
    // so we get a fresh CodeGen for each scope to avoid collisions.
    private CodeGen(CodeGen c) {
        this.cw = c.cw;
        this.mv = c.mv;
        this.packageAndClassName = c.packageAndClassName;
        this.traitOrObjectName = c.traitOrObjectName;
        this.springBoardClass = c.springBoardClass;

        this.ta = c.ta;
        this.pa = c.pa;
        this.fv = c.fv;
        this.topLevelOverloads = c.topLevelOverloads;
        this.exportedToUnambiguous = c.exportedToUnambiguous;
        this.overloadedNamesAndSigs = c.overloadedNamesAndSigs;

        this.lexEnv = new BATree<String,VarCodeGen>(c.lexEnv);

        this.inATrait = c.inATrait;
        this.inAnObject = c.inAnObject;
        this.inABlock = c.inABlock;
        this.emittingFunctionalMethodWrappers = c.emittingFunctionalMethodWrappers;
        this.currentTraitObjectDecl = c.currentTraitObjectDecl;
        
        this.initializedStaticFields_TO = c.initializedStaticFields_TO;
      
        this.component = c.component;
        this.ci = c.ci;
        this.env = c.env;

    }

    public CodeGen(Component c,
                   TypeAnalyzer ta, ParallelismAnalyzer pa, FreeVariables fv,
                   ComponentIndex ci, GlobalEnvironment env) {
        component = c;
        packageAndClassName = NamingCzar.javaPackageClassForApi(c.getName());
        String dotted = NamingCzar.javaPackageClassForApi(c.getName(), ".");
        JarOutputStream jos;
        try {
            jos = new JarOutputStream(new BufferedOutputStream( new FileOutputStream(NamingCzar.cache + dotted + ".jar")));
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.ta = ta;
        this.pa = pa;
        this.fv = fv;
        this.ci = ci;
        this.exportedToUnambiguous = new MultiMap<String, Function> ();

        this.cw = new CodeGenClassWriter(ClassWriter.COMPUTE_FRAMES, jos);
        cw.visitSource(NodeUtil.getSpan(c).begin.getFileName(), null);
        boolean exportsExecutable = false;
        boolean exportsDefaultLibrary = false;

        /*
         * Find every exported name, and make an entry mapping name to
         * API declarations, so that unambiguous names from APIs can
         * also be emitted.
         */
        List<APIName> exports = c.getExports();
        for (APIName apiname:exports) {
            if ( WellKnownNames.exportsMain(apiname.getText()) )
                exportsExecutable = true;
            if ( WellKnownNames.exportsDefaultLibrary(apiname.getText()) )
                exportsDefaultLibrary = true;
            ApiIndex api_index = env.api(apiname);
            Relation<IdOrOpOrAnonymousName, Function> fns = api_index.functions();
            for (IdOrOpOrAnonymousName name : fns.firstSet()) {
                if (name instanceof IdOrOp) {
                    Set<Function> defs = fns.matchFirst(name);
                    for (Function def : defs) {
                        IdOrOpOrAnonymousName ua_name = def.unambiguousName();
                        if (ua_name instanceof IdOrOp) {
                            IdOrOp ioo_name = (IdOrOp) name;
                            IdOrOp ioo_ua_name = (IdOrOp) ua_name;
                            if (! ioo_name.equals(ioo_ua_name)) {
                                // Add mapping ioo_name -> def to MultiMap
                                exportedToUnambiguous.putItem(
                                        ioo_name.getText(),
                                        def);
                            }
                        }
                    }
                }
            }
        }

        this.topLevelOverloads =
            sizePartitionedOverloads(ci.functions());

        this.overloadedNamesAndSigs = new HashSet<String>();
        this.lexEnv = new BATree<String,VarCodeGen>(StringHashComparer.V);
        this.env = env;


        String extendedJavaClass =
            exportsExecutable ? NamingCzar.fortressExecutable :
                                NamingCzar.fortressComponent ;

        cw.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER,
                 packageAndClassName, null, extendedJavaClass,
                 null);

        debug( "Compile: Compiling ", packageAndClassName );

        // Always generate the init method
        generateFieldsAndInitMethod(packageAndClassName, extendedJavaClass, Collections.<Param>emptyList());

        // If this component exports an executable API,
        // generate a main method.
        if ( exportsExecutable ) {
            generateMainMethod();
        }
    }


    // We need to expose this because nobody else helping CodeGen can
    // understand unqualified names (esp types) without it!
    public APIName thisApi() {
        return ci.ast().getName();
    }

    /** Factor out method call path so that we do it right
        everywhere we invoke a dotted method of any kind. */
    private void methodCall(IdOrOp method,
                            TraitType receiverType,
                            Type domainType, Type rangeType) {       
        String sig = NamingCzar.jvmSignatureFor(domainType, rangeType, thisApi());
        String methodName = method.getText();
        methodCall(methodName, receiverType, sig);
    }
    
    /** Factor out method call path so that we do it right
        everywhere we invoke a dotted method of any kind.
        Here, we eitehr know the Strings already for name and signature,
        or they could not easily be encoded into AST anyway.
    */
    private void methodCall(String methodName,
            TraitType receiverType,
            String sig) {
        int opcode;
        if (ta.typeCons(receiverType).unwrap().ast() instanceof TraitDecl &&
                !NamingCzar.fortressTypeIsSpecial(receiverType)) {
            opcode = INVOKEINTERFACE;
        } else {
            opcode = INVOKEVIRTUAL;
        }
        String methodClass = NamingCzar.jvmTypeDesc(receiverType, thisApi(), false);
        mv.visitMethodInsn(opcode, methodClass, methodName, sig);
    }

    private void generateMainMethod() {

        // We generate two methods.  First a springboard static main()
        // method that creates an instance of the class we are
        // generating, and then invokes the runExecutable(...) method
        // on that instance---this is RTS code that sets up
        // command-line argument access and initializes the work
        // stealing infrastructure.
        //
        // The second method is the compute() method, which is invoked
        // by the work stealing infrastructure after it starts up, and
        // simply calls through to the static run() method that must
        // occur in this component.  Without this little trampoline,
        // we need to special case the run() method during code
        // generation and the result is not reentrant (ie if we call
        // run() recursively we lose).

        mv = cw.visitCGMethod(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC, "main",
                            NamingCzar.stringArrayToVoid, null, null);
        mv.visitCode();
        // new packageAndClassName()
        mv.visitTypeInsn(Opcodes.NEW, packageAndClassName);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, packageAndClassName, "<init>",
                           NamingCzar.voidToVoid);

        // .runExecutable(args)
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                           NamingCzar.fortressExecutable,
                           NamingCzar.fortressExecutableRun,
                           NamingCzar.fortressExecutableRunType);

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(NamingCzar.ignore,NamingCzar.ignore);
        mv.visitEnd();
        // return

        mv = cw.visitCGMethod(Opcodes.ACC_PUBLIC, "compute",
                            NamingCzar.voidToVoid, null, null);
        mv.visitCode();
        // Call through to static run method in this component.
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, packageAndClassName, "run",
                           NamingCzar.voidToFortressVoid);
        // Discard the FVoid that results
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(NamingCzar.ignore, NamingCzar.ignore);
        mv.visitEnd();
    }

    private void generateFieldsAndInitMethod(String classFile, String superClass, List<Param> params) {
        // Allocate fields
        for (Param p : params) {
            // TODO need to spot for "final" fields.  Right now we assume final.
            String pn = p.getName().getText();
            Type pt = (Type)p.getIdType().unwrap();
            cw.visitField(Opcodes.ACC_PRIVATE + Opcodes.ACC_FINAL, pn,
                    NamingCzar.jvmTypeDesc(pt, thisApi(), true), null /* for non-generic */, null /* instance has no value */);
        }

        String init_sig = NamingCzar.jvmSignatureFor(params, "V", thisApi());
        mv = cw.visitCGMethod(Opcodes.ACC_PUBLIC, "<init>", init_sig, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superClass, "<init>", NamingCzar.voidToVoid);

        // Initialize fields.
        int pno = 1;
        for (Param p : params) {
            String pn = p.getName().getText();
            Type pt = (Type)p.getIdType().unwrap();

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, pno);
            mv.visitFieldInsn(Opcodes.PUTFIELD, classFile, pn,
                    NamingCzar.jvmTypeDesc(pt, thisApi(), true));
            pno++;
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(NamingCzar.ignore, NamingCzar.ignore);
        mv.visitEnd();
    }


    private void cgWithNestedScope(ASTNode n) {
        CodeGen cg = new CodeGen(this);
        n.accept(cg);
    }

    private void addLocalVar( VarCodeGen v ) {
        debug("addLocalVar ", v);
        lexEnv.put(v.name.getText(), v);
    }

    private void addStaticVar( VarCodeGen v ) {
        debug("addStaticVar ", v);
        lexEnv.put(v.name.getText(), v);
    }

    private VarCodeGen addParam(Param p) {
        VarCodeGen v =
            new VarCodeGen.ParamVar(p.getName(), (Type)p.getIdType().unwrap(), this);
        addLocalVar(v);
        return v;
    }

    private VarCodeGen addSelf() {
        Id tid = (Id)currentTraitObjectDecl.getHeader().getName();
        Id id = NodeFactory.makeId(NodeUtil.getSpan(tid), "self");
        Type t = STypesUtil.declToTraitType(currentTraitObjectDecl);
        VarCodeGen v = new VarCodeGen.ParamVar(id, t, this);
        addLocalVar(v);
        return v;
    }

    // Always needs context-sensitive null handling anyway.  TO FIX.
    // private VarCodeGen getLocalVar( ASTNode ctxt, IdOrOp nm ) {
    //     VarCodeGen r = getLocalVarOrNull(nm);
    //     if (r==null) return sayWhat(ctxt, "Can't find lexEnv mapping for local var");
    //     return r;
    // }

    private VarCodeGen getLocalVarOrNull( IdOrOp nm ) {
        debug("getLocalVar: ", nm);
        VarCodeGen r = lexEnv.get(NamingCzar.idOrOpToString(nm));
        if (r != null)
            debug("getLocalVar:", nm, " VarCodeGen = ", r, " of class ", r.getClass());
        else
            debug("getLocalVar:", nm, " VarCodeGen = null");
        return r;
    }

    private void popAll(int onStack) {
        if (onStack == 0) return;
        for (; onStack > 1; onStack -= 2) {
            mv.visitInsn(Opcodes.POP2);
        }
        if (onStack==1) {
            mv.visitInsn(Opcodes.POP);
        }
    }

    private void dumpTraitDecls(List<Decl> decls) {
        debug("dumpDecls", decls);
        for (Decl d : decls) {
            if (!(d instanceof FnDecl)) {
                sayWhat(d);
                return;
            }
            d.accept(this);
        }
    }

    /**
     * 
     * Generate a an instance method forwarding calls from fromTrait.fnl to
     * the static method toTrait.fnl.
     * 
     * @param fnl
     * @param inst
     * @param toTrait
     * @param fromTrait
     */
    private void generateForwardingFor(Functional fnl, StaticTypeReplacer inst,
                                       TraitType toTrait, TraitType fromTrait) {
        IdOrOp name = fnl.name();
        if (!(fnl instanceof HasSelfType))
            sayWhat(name, " method "+fnl+" doesn't appear to have self type.");
        HasSelfType st = (HasSelfType)fnl;
        List<Param> params = fnl.parameters();
        int arity = params.size();
        Type returnType = inst.replaceIn(fnl.getReturnType().unwrap());
        Type paramType = inst.replaceIn(NodeUtil.getParamType(params, NodeUtil.getSpan(name)));
        String sig = NamingCzar.jvmSignatureFor(
                         paramType,
                         NamingCzar.jvmTypeDesc(returnType, component.getName()),
                         0,
                         toTrait,
                         component.getName());
        String mname;
        int selfIndex = st.selfPosition();
        if (selfIndex != NO_SELF) {
            sig = Naming.removeNthSigParameter(sig, selfIndex+1);
            mname = fmDottedName(singleName(name), selfIndex);
        } else {
            mname = nonCollidingSingleName(name, sig,""); // What about static params?
            arity++;
        }
        String receiverClass = NamingCzar.jvmTypeDesc(toTrait, component.getName(), false) +
                               NamingCzar.springBoard;
        if (toTrait.equals(fromTrait)) receiverClass = springBoardClass;
        InstantiatingClassloader.forwardingMethod(cw, mname, ACC_PUBLIC, 0,
                                                  receiverClass, mname, INVOKESTATIC,
                                                  sig, sig, arity, true);
    }


    /**
      Fortress objects, compiled into classes, will class-inherit default methods
      defined in the first trait they extend (they are compiled to extend its
      trait default methods, which are in a class), but not default methods
      defined in subsequent extended traits.

      To fix this forwarding methods are added to the object to call the
      non-inherited default methods (where appropriate, meaning not defined
      in either the object itself or an earlier trait).

      Forwarding methods are also added to the compiled default-trait-methods
      classes, so that if they are inherited, they will supply all the necessary
      methods, and to ensure that the forwarding analysis is entirely local
      (that is, this trait/object, its first extended trait, and subsequent
      extended traits).

      includeCurrent is true for traits.  default-trait-method bodies are static,
      but instance methods are required for inheritance and dispatch.  For traits,
      in order to inherit the own (current) default methods, instance-to-static
      forwarding methods must be also be added.

      The conditions for adding a forwarding method are:

          1) Inherited through the second and subsequent supertraits -OR-
             includeCurrent and defined in the current trait.

          2) Are not also inherited through the first supertrait
             (due to joins in type hierarchy)

          3) Are not overridden by a method in the present trait or object
          
     Note the assumption that trait inheritance is normalized.
     If "A extends {B,C}" has been normalized,
     then B does not extend C and C does not extend B.
     That is, the extends clause is minimal.
     (There may be some issues with normalized inheritance and
      comprises clauses.)
    */
    private void dumpMethodChaining(String [] superInterfaces, boolean includeCurrent) {

        /*
         * If the number of supertraits is 0, there is nothing to inherit.
         * If it is one, inheritance comes via the class hierarchy.
         * If not includeCurrent, then there are no own-trait methods to forward.
         *
         * In that case, there is nothing to do.
         */
        if (!includeCurrent && superInterfaces.length <= 1) return;

        TraitType currentTraitObjectType = STypesUtil.declToTraitType(currentTraitObjectDecl);
        List<TraitTypeWhere> extendsClause = NodeUtil.getExtendsClause(currentTraitObjectDecl);
        Relation<IdOrOpOrAnonymousName, scala.Tuple3<Functional, StaticTypeReplacer, TraitType>>
            alreadyIncluded;
        /*
         * Initialize alreadyIncluded to empty, or to the inherited methods
         * from the first t
         */
        if (extendsClause.size() == 0) {
            alreadyIncluded =
                new IndexedRelation<IdOrOpOrAnonymousName,
                             scala.Tuple3<Functional, StaticTypeReplacer, TraitType>>();
        } else {
            alreadyIncluded = STypesUtil.inheritedMethods(extendsClause.subList(0,1), ta);
        }

        /*
         * Apparently allMethods returns the transitive closure of all methods
         * declared in a particular trait or object and the types it extends.
         * Iterate over all of them, noting the ones with bodies, that are not
         * already defined in this type or the first extending type (those
         * defined in this type are conditional on includeCurrent).
         *
         * Note that extends clauses should be minimal by this point, or at
         * least as-if minimal; we don't want to be dealing with duplicated
         * methods that would not trigger overriding by the meet rule (if the
         * extends clause is minimal, and a method is defined twice in the
         * extends clause, then it needs to be disambiguated in this type).
         */
        Relation<IdOrOpOrAnonymousName, scala.Tuple3<Functional, StaticTypeReplacer, TraitType>>
            toConsider = STypesUtil.allMethods(currentTraitObjectType, ta);
        // System.err.println("Considering chains for "+tt);
        for (Pair<IdOrOpOrAnonymousName,scala.Tuple3<Functional, StaticTypeReplacer, TraitType>>
                 assoc : toConsider) {
            scala.Tuple3<Functional, StaticTypeReplacer, TraitType> tup = assoc.second();
            TraitType tupTrait = tup._3();
            StaticTypeReplacer inst = tup._2();
            Functional fnl = tup._1();
            // System.err.println("  "+assoc.first()+" "+tupTrait+" "+fnl);
            /* Skip non-definitions. */
            if (!fnl.body().isSome()) continue;

            /* If defined in the current trait. */
            if (tupTrait.equals(currentTraitObjectType)) {
                if (includeCurrent) {
                    generateForwardingFor(fnl, inst, tupTrait, currentTraitObjectType);
                }
                continue;
            }
            boolean alreadyThere = false;

            /* Iterate over tuples for
             * already-defined methods
             * whose names match
             * that of the method being considered (assoc.first()).
             *
             * If the trait of the method being considered,
             * and the trait of any name-matching already included method
             * match, then don't generate a wrapper.
             *
             * DOES THIS HAVE A BUG IN IT?  WHAT ABOUT OVERLOADED METHODS?
             * Their names will match, but the parameter types need not.
             */
            for (scala.Tuple3<Functional, StaticTypeReplacer, TraitType> tupAlready :
                     alreadyIncluded.matchFirst(assoc.first())) {
                if (tupAlready._3().equals(tupTrait)) {
                    // System.err.println("    " + fnl + " already imported by first supertrait.");
                    alreadyThere = true;
                    break;
                }
            }
            if (alreadyThere) continue;
            generateForwardingFor(fnl, inst, tupTrait, currentTraitObjectType);
        }
    }

    /**
     * Similar to dumpMethodChaining, except that this generates
     * the erased versions of methods from generic traits and objects.
     *
     * @param superInterfaces
     * @param isTrait
     */

    private void dumpErasedMethodChaining(String [] superInterfaces, boolean isTrait) {

        /*
         * TODO: THIS CODE IS CLOSE BUT NOT FULLY CORRECT.
         *
         * Hypothesized screw case:
         *
         * trait isGeneric[\T\]
         *   f(self, x:T):T
         * end
         *
         * trait hidesGeneric extends isGeneric[\ZZ\]
         *   f(self, x:ZZ):ZZ = 1
         * end
         *
         * trait firstExtended end
         *
         * object O extends { firstExtended, hidesGeneric } end
         *
         * Because "hidesGeneric" is second in the extends clause, its
         * will not be class-inherited by O.  Because it supplies f,
         * it will override (in the query methods below) the f declared
         * in isGeneric.  However, hidesGeneric is not generic, so no
         * erased function will be created.
         *
         */
        TraitType tt = STypesUtil.declToTraitType(currentTraitObjectDecl);
        List<TraitTypeWhere> extendsClause = NodeUtil.getExtendsClause(currentTraitObjectDecl);

        Relation<IdOrOpOrAnonymousName, scala.Tuple3<Functional, StaticTypeReplacer, TraitType>>
            fromFirst;

        /*
         * Initialize alreadyIncluded to empty, or to the inherited methods
         * from the first t
         */
        if (extendsClause.size() == 0) {
            fromFirst =
                new IndexedRelation<IdOrOpOrAnonymousName,
                             scala.Tuple3<Functional, StaticTypeReplacer, TraitType>>();
        } else {
            fromFirst = STypesUtil.inheritedMethods(extendsClause.subList(0,1), ta);
        }

        Relation<IdOrOpOrAnonymousName, scala.Tuple3<Functional, StaticTypeReplacer, TraitType>>
        fromSelf = STypesUtil.inheritedMethods(Useful.list(NodeFactory.makeTraitTypeWhere(tt)), ta);

        /* Need to filter alreadyIncluded to contain only those methods that come
         * from generics -- we don't want a non-generic to shadow a generic.
         * (Should be able to handle this below instead of here.)
         */

        /*
         * Apparently allMethods returns the transitive closure of all methods
         * declared in a particular trait or object and the types it extends.
         * Iterate over all of them, noting the ones with bodies, that are not
         * already defined in this type or the first extending type (those
         * defined in this type are conditional on includeCurrent).
         *
         * Note that extends clauses should be minimal by this point, or at
         * least as-if minimal; we don't want to be dealing with duplicated
         * methods that would not trigger overriding by the meet rule (if the
         * extends clause is minimal, and a method is defined twice in the
         * extends clause, then it needs to be disambiguated in this type).
         */
        Relation<IdOrOpOrAnonymousName, scala.Tuple3<Functional, StaticTypeReplacer, TraitType>>
            toConsider = STypesUtil.allMethods(tt, ta);
        // System.err.println("Considering chains for "+tt);

        for (Pair<IdOrOpOrAnonymousName,scala.Tuple3<Functional, StaticTypeReplacer, TraitType>>
                 assoc : toConsider) {

            scala.Tuple3<Functional, StaticTypeReplacer, TraitType> tup = assoc.second();
            TraitType tupTrait = tup._3();
            StaticTypeReplacer inst = tup._2();
            Functional fnl = tup._1();

            /* Not generic, no need to remove erased method. */
            if (tupTrait.getArgs().size() == 0)
                continue;

            /* Need to define a wrapper even if there is no body.
             * consider case where
             * trait T[\S\]
             *   f(x:T):T
             * end
             * object O extends T[\ZZ\]
             *   f(x:ZZ):ZZ=1
             * end
             *
             * The information for O.f will not mention the need to define an
             * erased wrapper for T[\S\].f
             */


            /* Iterate over tuples for
             * already-defined methods
             * whose names match
             * that of the method being considered (assoc.first()).
             *
             * If the trait of the method being considered,
             * and the trait of any name-matching already included method
             * match, then don't generate a wrapper.
             *
             * DOES THIS HAVE A BUG IN IT?  WHAT ABOUT OVERLOADED METHODS?
             * Their names will match, but the parameter types need not.
             */

            /* Is it already erased in the first supertype?
             * If so, the erasure will be inherited from there.
             */
            boolean alreadyThere =
                isAlreadyErased(fromFirst, assoc, tupTrait);
            if (alreadyThere)
                continue;

            /*
             * Not erased in parent, therefore, if it is declared in this
             * type, emit an erased version.
             */
            if (tupTrait.equals(tt)) {
                generateErasedForwardingFor(fnl, inst, tupTrait, tt);
                continue;
            }

            /*
             * Is it defined in this type already?
             * If so, do not repeat the definition from a supertype.
             * But if not, then we need an erased implementation.
             */
            alreadyThere =
                isAlreadyErased(fromSelf, assoc, tupTrait);
            if (alreadyThere)
                continue;

            generateErasedForwardingFor(fnl, inst, tupTrait, tt);
        }
    }


    /**
     * @param alreadyErased
     * @param fnl_name
     * @param fnl_Trait
     * @return
     */
    private boolean isAlreadyErased(
            Relation<IdOrOpOrAnonymousName, scala.Tuple3<Functional, StaticTypeReplacer, TraitType>> alreadyErased,
            Pair<IdOrOpOrAnonymousName,scala.Tuple3<Functional, StaticTypeReplacer, TraitType>>
            assoc, TraitType fnl_Trait) {
        IdOrOpOrAnonymousName fnl_name = assoc.first();
        boolean alreadyThere = false;
        for (scala.Tuple3<Functional, StaticTypeReplacer, TraitType> tupAlready :
                 alreadyErased.matchFirst(fnl_name)) {
            /* Non-generics cannot shadow generics. */
            if (tupAlready._3().getArgs().size() == 0)
                continue;
            /*
             * This test is not right; it needs to pass in the entire function,
             * and compare parameter lists for collision.
             */
            if (tupAlready._3().equals(fnl_Trait)) {
                // System.err.println("    " + fnl + " already imported by first supertrait.");
                alreadyThere = true;
                break;
            }
        }
        return alreadyThere;
    }


    /**
     * Generates forwarding methods for type-erased dotted methods
     * that are generated as companions to top-level functional
     * methods.  The methods for which this needs to be done are:
     *
     *  * declared in a generic trait/object
     *  * are functional (have an explicit self parameter)
     *  * mention a static parameter type (from the declaring trait/object)
     *    in their parameter list (this is optional -- ideally we spot for
     *    this, but we can over-generate initially, because we will need to
     *    do the tricky test in overloading code to spot this case).
     *
     *  The methods so generated will be tagged with a $ERASED suffix to
     *  avoid clashes.
     *
     *  The code cannot do blind forwarding because casts must be
     *  supplied for the erased types in the forwarding method.
     *  
     *  NOTE: the code that this generates is not yet executed, as far as I know.
     *
     * @param fnl
     * @param inst
     * @param toTrait
     * @param fromTrait
     */
    private void generateErasedForwardingFor(Functional fnl,
            StaticTypeReplacer inst, TraitType toTrait, TraitType fromTrait) {
        /* No need to (un)erase for non-generic */
        if (toTrait.getArgs().size() == 0)
            return;
        /*
         * TODO - starting with a copy of generateForwardingFor
         * The goal is to obtain an erased-signature wrapper
         * function that forwards to the properly typed target.
         * The original code form generateForwardingFor obtains
         * an appropriate target signature.
         */
        IdOrOp name = fnl.name();
        if (!(fnl instanceof HasSelfType))
            sayWhat(name, " method " + fnl
                    + " doesn't appear to have self type.");
        HasSelfType st = (HasSelfType) fnl;
        List<Param> params = fnl.parameters();
        int arity = params.size();

        Type returnType = inst.replaceIn(fnl.getReturnType().unwrap());
        Type paramType = inst.replaceIn(NodeUtil.getParamType(params, NodeUtil
                .getSpan(name)));
        String sig = NamingCzar.jvmSignatureFor(paramType, NamingCzar
                .jvmTypeDesc(returnType, component.getName()), -1, toTrait,
                component.getName());

        // erase these using toTrait.
        // what's the right way to do this?  Use component index to lookup trait name,
        // to get the traitdecl, to get the staticparams.

        Map<Id, TypeConsIndex> types = ci.typeConses();
        TypeConsIndex tci = types.get(toTrait.getName());
        List<StaticParam> sp_list = tci.staticParameters();

        TypeAnalyzer eta = ta.extend(sp_list, Option.<WhereClause>none());

        // GroundBound is not quite right, because we have erased type names
        // for ilks that are not available to legal Fortress.  Perhaps
        // we can pun them as generics with no arguments.

        Type erasedReturnType = eta.groundBound(fnl.getReturnType().unwrap());
        Type erasedParamType = eta.groundBound(NodeUtil.getParamType(params, NodeUtil
                .getSpan(name)));
        String erasedSig = NamingCzar.jvmSignatureFor(erasedParamType, NamingCzar
                .jvmTypeDesc(erasedReturnType, component.getName()), -1, eta.groundBound(toTrait),
                component.getName());
        String mname;
        int selfIndex = st.selfPosition();

        List<Type> from_type_list =
            normalizeParamsToList(erasedParamType);

        List<Type> to_type_list =
            normalizeParamsToList(paramType);

        if (selfIndex != NO_SELF) {
            erasedSig = Naming.removeNthSigParameter(erasedSig, selfIndex );
            sig = Naming.removeNthSigParameter(sig, selfIndex );
            mname = fmDottedName(singleName(name), selfIndex);
            from_type_list = Useful.removeIndex(selfIndex, from_type_list);
            to_type_list = Useful.removeIndex(selfIndex, to_type_list);
        } else {
            mname = nonCollidingSingleName(name, erasedSig, ""); // Need to figure this out later.
            // I think it might need to have $ERASED added to it anyway.
            // But we could overload those, too, couldn't we?
            arity++;
        }

        String receiverClass = NamingCzar.jvmTypeDesc(toTrait, component
                .getName(), false);

//        InstantiatingClassloader.forwardingMethod(cw, mname, ACC_PUBLIC, 0,
//                receiverClass, mname, INVOKEVIRTUAL, sig, arity, true);
    }

    /**
     * @param paramType
     * @return
     */
    private List<Type> normalizeParamsToList(Type paramType) {
        return paramType instanceof TupleType ? ((TupleType)paramType).getElements() : Useful.list(paramType);
    }

    private void addLineNumberInfo(ASTNode x) {
        addLineNumberInfo(mv, x);
    }

    private void addLineNumberInfo(CodeGenMethodVisitor m, ASTNode x) {
        org.objectweb.asm.Label bogus_label = new org.objectweb.asm.Label();
        m.visitLabel(bogus_label);
        Span span = NodeUtil.getSpan(x);
        SourceLoc begin = span.getBegin();
        SourceLoc end = span.getEnd();
        String fileName = span.getFileName();
        m.visitLineNumber(begin.getLine(), bogus_label);
    }

    /**
     * @param x
     * @param arrow
     * @param pkgAndClassName
     * @param methodName
     */
    private void callStaticSingleOrOverloaded(FunctionalRef x,
            com.sun.fortress.nodes.Type arrow, String pkgAndClassName,
            String methodName) {

        debug("class = ", pkgAndClassName, " method = ", methodName );
        addLineNumberInfo(x);

        Pair<String, String> method_and_signature =
            resolveMethodAndSignature(x, arrow, methodName);

        mv.visitMethodInsn(Opcodes.INVOKESTATIC, pkgAndClassName,
                method_and_signature.first(), method_and_signature.second());

    }

    /**
     * @param x
     * @param arrow
     * @param methodName
     * @return
     * @throws Error
     */
    private Pair<String, String> resolveMethodAndSignature(ASTNode x,
            com.sun.fortress.nodes.Type arrow, String methodName) throws Error {
        Pair<String, String> method_and_signature = null;
        String signature = null;

        if ( arrow instanceof ArrowType ) {
            // TODO should this be non-colliding single name instead?
            // answer depends upon how intersection types are normalized.
            // conservative answer is "no".
            // methodName = Naming.mangleIdentifier(methodName);
            signature = NamingCzar.jvmMethodDesc(arrow, component.getName());

        } else if (arrow instanceof IntersectionType) {
            IntersectionType it = (IntersectionType) arrow;
            methodName = OverloadSet.actuallyOverloaded(it, paramCount) ?
                    OverloadSet.oMangle(methodName) : methodName;

            signature = OverloadSet.getSignature(it, paramCount, ta);

        } else {
                sayWhat( x, "Neither arrow nor intersection type: " + arrow );
        }
        return new Pair<String,String>(methodName, signature);
    }

    // paramCount communicates this information from call to function reference,
    // as it's needed to determine type descriptors for methods.
    private int paramCount = -1;

    /**
     * @param y
     */
    private void pushInteger(int y) {
        switch (y) {
        case 0:
            mv.visitInsn(Opcodes.ICONST_0);
            break;
        case 1:
            mv.visitInsn(Opcodes.ICONST_1);
            break;
        case 2:
            mv.visitInsn(Opcodes.ICONST_2);
            break;
        case 3:
            mv.visitInsn(Opcodes.ICONST_3);
            break;
        case 4:
            mv.visitInsn(Opcodes.ICONST_4);
            break;
        case 5:
            mv.visitInsn(Opcodes.ICONST_5);
            break;
        default:
            mv.visitLdcInsn(y);
            break;
        }

    }

    private void allSayWhats() {
        return; // This is a great place for a breakpoint!
    }

    private <T> T sayWhat(ASTNode x) {
        allSayWhats();
        throw new CompilerError(x, "Can't compile " + x);
    }

    private <T> T sayWhat(Node x) {
        if (x instanceof ASTNode)
            sayWhat((ASTNode) x);
        allSayWhats();
        throw new CompilerError("Can't compile " + x);
    }

    private <T> T sayWhat(ASTNode x, String message) {
        allSayWhats();
        throw new CompilerError(x, message + " node = " + x);
    }

    private void debug(Object... message){
        Debug.debug(Debug.Type.CODEGEN,1,message);
    }

    private void doStatements(List<Expr> stmts) {
        int onStack = 0;
        if (stmts.isEmpty()) {
            pushVoid();
            return;
        }
        for ( Expr e : stmts ) {
            popAll(onStack);
            e.accept(this);
            onStack = 1;
            // TODO: can we have multiple values on stack in future?
            // Whither primitive types?
            // May require some tracking of abstract stack state.
            // For now we always have 1 pointer on stack and this doesn't
            // matter.
        }
    }

    public void defaultCase(Node x) {
        System.out.println("defaultCase: " + x + " of class " + x.getClass());
        sayWhat(x);
    }

    public void forImportStar(ImportStar x) {
        // do nothing, don't think there is any code go generate
    }

    public void forBlock(Block x) {
        if (x.isAtomicBlock()) {
            sayWhat(x, "Can't generate code for atomic block yet.");
        }
        boolean oldInABlock = inABlock;
        inABlock = true;
        debug("forBlock ", x);
        doStatements(x.getExprs());
        inABlock=oldInABlock;
    }

    public void forChainExpr(ChainExpr x) {
        debug( "forChainExpr", x);
        Expr first = x.getFirst();
        List<Link> links = x.getLinks();
        debug( "forChainExpr", x, " about to call accept on ",
               first, " of class ", first.getClass());
        first.accept(this);
        Iterator<Link> i = links.iterator();
        if (links.size() != 1)
            throw new CompilerError(x, x + "links.size != 1");
        Link link = i.next();
        link.getExpr().accept(this);
        debug( "forChainExpr", x, " about to call accept on ",
               link.getOp(), " of class ", link.getOp().getClass());
        int savedParamCount = paramCount;
        try {
            // TODO is this the general formula?
            paramCount = links.size() + 1;
            link.getOp().accept(this);
        } finally {
            paramCount = savedParamCount;
        }

        debug( "We've got a link ", link, " an op ", link.getOp(),
               " and an expr ", link.getExpr(), " What do we do now");
    }

    public void forComponent(Component x) {
        debug("forComponent ",x.getName(),NodeUtil.getSpan(x));

        for ( Import i : x.getImports() ) {
            i.accept(this);
        }

        // determineOverloadedNames(x.getDecls() );

        // Must do this first, to get local decls right.
        overloadedNamesAndSigs = generateTopLevelOverloads(thisApi(), topLevelOverloads, ta, cw, this);

        /* Need wrappers for the API, too. */
        generateUnambiguousWrappersForApi();

        // Must process top-level values next to make sure fields end up in scope.
        for (Decl d : x.getDecls()) {
            if (d instanceof ObjectDecl) {
                this.forObjectDeclPrePass((ObjectDecl) d);
            } else if (d instanceof VarDecl) {
                this.forVarDeclPrePass((VarDecl)d);
            }
        }

        // Static initializer for this class.
        // Since all top-level fields and singleton objects are singleton inner classes,
        // this does nothing.
        mv = cw.visitCGMethod(Opcodes.ACC_STATIC,
                "<clinit>",
                "()V",
                null,
                null);

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(NamingCzar.ignore, NamingCzar.ignore);
        mv.visitEnd();

        for ( Decl d : x.getDecls() ) {
            d.accept(this);
        }

        cw.dumpClass( packageAndClassName );
    }

    public void forDecl(Decl x) {
        sayWhat(x, "Can't handle decl class "+x.getClass().getName());
    }


    public void forDo(Do x) {
        // TODO: these ought to occur in parallel!
        debug("forDo ", x);
        int onStack = 0;
        for ( Block b : x.getFronts() ) {
            popAll(onStack);
            b.accept(this);
            onStack = 1;
        }
    }

    // TODO: arbitrary-precision version of FloatLiteralExpr, correct
    // handling of types other than double (float should probably just
    // truncate, but we want to warn if we lose bits I expect).
    public void forFloatLiteralExpr(FloatLiteralExpr x) {
        debug("forFloatLiteral ", x);
        double val = x.getIntPart().doubleValue() +
            (x.getNumerator().doubleValue() /
             Math.pow(x.getDenomBase(), x.getDenomPower()));
        mv.visitLdcInsn(val);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                           NamingCzar.internalFortressFloatLiteral, NamingCzar.make,
                           NamingCzar.makeMethodDesc(NamingCzar.descDouble,
                                                     NamingCzar.descFortressFloatLiteral));
    }

    /**
     * Generate the closure class (instea
     * @param x
     * @param name
     * @param selfIndex
     */
    private String generateGenericFunctionClass(FnDecl x, IdOrOp name,
                                            int selfIndex) {
        /*
         * Different plan for static parameter decls;
         * instead of a method name, we are looking for an
         * inner class name, similar to how these are constructed
         * for traits.
         *
         * The inner class name has the form
         *
         * PKG.componentGEAR$function[\t1;t2;n3;o4\]ENVELOPEarrow[\d1;d2;r\]
         *
         * where
         * PKG is package name
         * component is component name
         * GEAR is Unicode "GEAR"
         * function is function name
         * t1, t2, n3, o4 encode static parameter kinds
         * ENVELOPE is unicode Envelope (just like a closure)
         * arrow is "Arrow", the stem on a generic arrow type
         * d1, d2, r are the type parameters of the arrow type.
         *
         * These classes will have all the attributes required of a
         * closure class, except that the static parameters will be
         * dummies to be replaced at instantiation time.
         */

        /*
         * Need to modify the
         * signature, depending on
         * circumstances.
         */

        Map<String, String> xlation = null; // this may disappear
        List<String> splist = new ArrayList<String>(); // necessary for metadata
        String sparams_part = genericDecoration(x, xlation, splist);

        FnHeader header = x.getHeader();
        List<Param> params = header.getParams();
        Type returnType = header.getReturnType().unwrap();
        Expr body = x.getBody().unwrap();

        String sig =
            NamingCzar.jvmSignatureFor(NodeUtil.getParamType(x),
                                       returnType, component.getName());

        /* TODO Really, the canonicalization of the type names should occur
         * in static analysis.  This has to use names that will be known
         * at the reference site, so for now we are using the declared
         * names.  In rare cases, this might lead to a problem.
         */
        ArrowType at = fndeclToType(x); // use the pre-rewritten type.
        String generic_arrow_type = NamingCzar.jvmTypeDesc(at, thisApi(), false);
        String mname;

        // TODO different collision rules for top-level and for
        // methods. (choice of mname)

        if (selfIndex != NO_SELF) {
            sig = Naming.removeNthSigParameter(sig, selfIndex);
            mname = fmDottedName(singleName(name), selfIndex);
        } else {
            mname = nonCollidingSingleName(name, sig, generic_arrow_type);
        }

        // TODO refactor, this is computed in another place.
        
        /* 
         * This may be a mistake, but class name and the file name do not match.
         * The reason for this is that the class is not really a class; it is 
         * a template to be filled in.  The class name needs parameter slots
         * embedded in it so that they can be replaced at instantiation time,
         * but it is more convenient (but perhaps not 100% necessary at this
         * point, given the use of metadata) to omit the parameters from the
         * container's file name.
         */
        String PCN_for_class =
            Naming.genericFunctionPkgClass(packageAndClassName, mname,
                                               sparams_part, generic_arrow_type);
        
        String PCN_for_file =
            Naming.genericFunctionPkgClass(packageAndClassName, mname,
                        makeTemplateSParams(sparams_part) , generic_arrow_type);

        // System.err.println(PCN);

        CodeGen cg = new CodeGen(this);
        cg.cw = new CodeGenClassWriter(ClassWriter.COMPUTE_FRAMES, cw);

        // This creates the closure bits
        InstantiatingClassloader.closureClassPrefix(PCN_for_class, cg.cw, PCN_for_class, sig);

        // Code below cribbed from top-level/functional/ordinary method
        int modifiers = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC ;

        cg.generateActualMethodCode(modifiers, mname, sig, params, selfIndex,
                                    selfIndex != NO_SELF, body);

        cg.cw.dumpClass(PCN_for_file, splist);
        
        return PCN_for_class;
    }
    
    /**
     * Generate a generic method with a body.
     * 
     * Two methods must be generated.
     * 
     * One method is an instance method, like
     * a forwarding method, that takes a precomputed hashcode and a String
     * encoding the static parameters of the generic method (as they were
     * inferred or provided).  It returns a closure (one that has no particularly
     * interesting environment, really just a function pointer) made by specializing
     * the second method.  Obviously, these are cached.
     * 
     * The static parameters are slightly modified to include an encoding of
     * the static type of "self" at the call site; this is necessary to get
     * the type right on the returned arrow type, which makes "self" explicit.
     * 
     * The second method is a static method as if for a generic function, with
     * the signature of the original method, except that self is explicitly
     * prepended.  This is very similar to a trait default method, except that
     * it is generic.
     * 
     * @param x
     * @param name
     * @param sparams 
     * @param params
     * @param selfIndex
     * @param savedInATrait 
     * @param returnType
     * @param inAMethod
     * @param body
     */
    private void generateGenericMethod(FnDecl x, IdOrOp name,
            List<StaticParam> sparams, List<Param> params, int self_index,
            boolean savedInATrait, Type returnType,
            boolean inAMethod, Expr body) {

        /*
         * First, create a modified FnDecl that looks like a top-level generic
         * method.
         * 
         * Add a new static parameter onto the front of the SP list; that will
         * be the call-site type of "self".
         * 
         * Next remove any explicit self parameter from within the parameter
         * list, and create a new explicit self parameter, with type given by
         * the new SP, and put that at the front of the parameter list (this
         * may require a minor bit of type-replumbing for self, not clear how
         * that works yet).
         * 
         */
        Span sp_span = x.getInfo().getSpan();
        Id sp_name = NodeFactory.makeId(sp_span, Naming.UP_INDEX);
        Id p_name = NamingCzar.selfName(sp_span);
        StaticParam new_sp = NodeFactory.makeTypeParam(sp_span, Naming.UP_INDEX);
        List<StaticParam> new_sparams = new InsertedList(sparams, 0, new_sp);
        Param new_param = NodeFactory.makeParam(p_name, NodeFactory.makeVarType(sp_span, sp_name));
        List<Param> new_params = new InsertedList((self_index != NO_SELF ? new DeletedList(params, self_index) : params), 0, new_param);
        
        FnHeader xh = x.getHeader();
        
        FnDecl new_fndecl = NodeFactory.makeFnDecl(sp_span, xh.getMods(), xh.getName(),
                new_sparams, new_params, xh.getReturnType(), xh.getThrowsClause(),
                xh.getWhereClause(), xh.getContract(), x.getUnambiguousName(), Option.some(body), x.getImplementsUnambiguousName());
                
        // This is not right yet -- the name is wrong.
        String TO_method_name = currentTraitObjectDecl.getHeader().getName().stringName() + Naming.UP_INDEX + name.getText();
        
        String template_class_name = 
            generateGenericFunctionClass(new_fndecl, NodeFactory.makeId(sp_span, TO_method_name), NO_SELF);
        
        
        String method_name = genericMethodName(x);
        
        if (savedInATrait) {
            // does this get generated at the forwarding site instead?
        } else {
            generateGenericMethodClosureFinder(method_name, template_class_name);
        }
        
    }
    
    private static BAlongTree sampleLookupTable = new BAlongTree();


    /**
     * @param name
     * @param sparams
     */
    private String genericMethodName(FnDecl x) {

        IdOrOp name = (IdOrOp) (x.getHeader().getName());
        ArrowType at = fndeclToType(x);
        
        return genericMethodName(name, at);    
    }
    
    // DRC-WIP

    private String genericMethodName(IdOrOp name, ArrowType at) {
        String generic_arrow_type = NamingCzar.jvmTypeDesc(at, thisApi(),
                false);
        
        /* Just append the schema. 
           Do not separate with HEAVY_X, because schema may depend on 
           parameters from parent trait/object.
           (HEAVY_X stops substitution in instantiator).
           */
        return name.getText() + Naming.UP_INDEX + generic_arrow_type;
    }
    
    private final static String genericMethodClosureFinderSig = "(JLjava/lang/String;)Ljava/lang/Object;";
    
    /**
     * Generates the method that is called to find the closure for a generic method.
     * The method name is some minor frob on the Fortress method name, but it needs
     * the schema to help avoid overloading conflicts on the name.
     * 
     *  <pre>
     *  private static BAlongTree sampleLookupTable = new BAlongTree();
     *  public Object sampleLookup(long hashcode, String signature) {
     *  Object o = sampleLookupTable.get(hashcode);
     *  if (o == null) 
     *     o = findGenericMethodClosure(hashcode,
     *                                  sampleLookupTable,
     *                                  "template_class_name",
     *                                  signature);
     *  return o;
     *  }
     *  </pre>
     *  
     * @param template_class_name
     */
    private void generateGenericMethodClosureFinder(final String method_name, String template_class_name) {

        // DRC-WIP
        final String class_file = traitOrObjectName;
        final String table_name = method_name + Naming.HEAVY_X + "table";
        final String table_type = "com/sun/fortress/runtimeSystem/BAlongTree";
        
        initializedStaticFields_TO.add(new InitializedStaticField() {

            @Override
            public void forClinit(MethodVisitor mv) {
                mv.visitTypeInsn(NEW, table_type);
                mv.visitInsn(DUP);
                mv.visitMethodInsn(INVOKESPECIAL, table_type, "<init>", "()V");
                mv.visitFieldInsn(PUTSTATIC, class_file, table_name, "L"+table_type+";");                
            }

            @Override
            public String asmName() {
                return table_name;
            }

            @Override
            public String asmSignature() {
                return "L"+table_type+";";
            }
            
        });
        
        CodeGenMethodVisitor mv = cw.visitCGMethod(ACC_PUBLIC, method_name, genericMethodClosureFinderSig, null, null);
        mv.visitCode();
        Label l0 = new Label();
        mv.visitLabel(l0);
        //mv.visitLineNumber(1331, l0);
        mv.visitFieldInsn(GETSTATIC, class_file, table_name, "L"+table_type+";");
        mv.visitVarInsn(LLOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, table_type, "get", "(J)Ljava/lang/Object;");
        mv.visitVarInsn(ASTORE, 4);
        Label l1 = new Label();
        mv.visitLabel(l1);
        //mv.visitLineNumber(1332, l1);
        mv.visitVarInsn(ALOAD, 4);
        Label l2 = new Label();
        mv.visitJumpInsn(IFNONNULL, l2);
        Label l3 = new Label();
        mv.visitLabel(l3);
        //mv.visitLineNumber(1333, l3);
        mv.visitVarInsn(LLOAD, 1);
        mv.visitFieldInsn(GETSTATIC, class_file, table_name,"L"+table_type+";");
        mv.visitLdcInsn(template_class_name);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESTATIC, "com/sun/fortress/runtimeSystem/InstantiatingClassLoader", "findGenericMethodClosure", "(JLcom/sun/fortress/runtimeSystem/BAlongTree;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;");
        mv.visitVarInsn(ASTORE, 4);
        mv.visitLabel(l2);
        //mv.visitLineNumber(1335, l2);
        mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {"java/lang/Object"}, 0, null);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitInsn(ARETURN);
        Label l4 = new Label();
        mv.visitLabel(l4);
        mv.visitLocalVariable("this", "L"+class_file+";", null, l0, l4, 0);
        mv.visitLocalVariable("hashcode", "J", null, l0, l4, 1);
        mv.visitLocalVariable("signature", "Ljava/lang/String;", null, l0, l4, 3);
        mv.visitLocalVariable("o", "Ljava/lang/Object;", null, l1, l4, 4);
        mv.visitMaxs(5, 5);
        mv.visitEnd();  
    }


    private void generateTraitDefaultMethod(FnDecl x, IdOrOp name,
                                            List<Param> params,
                                            int selfIndex,
                                            Type returnType,
                                            boolean inAMethod,
                                            Expr body) {
        int modifiers = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;

        Type traitType = STypesUtil.declToTraitType(currentTraitObjectDecl);

        /* Signature includes explicit leading self
           First version of sig includes duplicate self for
           functional methods, which is then cut out.
        */
        String sig = NamingCzar.jvmSignatureFor(
                         NodeUtil.getParamType(x),
                         NamingCzar.jvmTypeDesc(returnType,
                                                component.getName()),
                         0,
                         traitType,
                         component.getName());

        // TODO different collision rules for top-level and for
        // methods.
        String mname;
        int n = params.size();
        if (selfIndex != NO_SELF) {
            sig = Naming.removeNthSigParameter(sig, selfIndex+1);
            mname = fmDottedName(singleName(name), selfIndex);
        } else {
            mname = nonCollidingSingleName(name, sig, ""); // static params?
            n++;
        }

        CodeGen cg = new CodeGen(this);
        cg.generateActualMethodCode(modifiers, mname, sig, params, selfIndex,
                                    true, body);

        /*
         * Next emit an abstract redirecter, this makes life better
         * for our primitive type story.
         */

        // Dotted method; downcast self and
        // forward to static method in springboard class
        // with explicit self parameter.

        // InstantiatingClassloader.forwardingMethod(cw, mname, ACC_PUBLIC, 0,
        //                                           springBoardClass, mname, INVOKESTATIC,
        //                                           sig, n, true);
    }

    private void generateFunctionalBody(FnDecl x, IdOrOp name,
                                        List<Param> params,
                                        int selfIndex,
                                        Type returnType,
                                        boolean inAMethod,
                                        boolean savedInAnObject,
                                        Expr body) {
        /* options here:
         *  - functional method in object
         *  - normal method in object
         *  - top level
         */
        int modifiers = Opcodes.ACC_PUBLIC;

        /*
         * Need to modify the
         * signature, depending on
         * circumstances.
         */
        String sig = NamingCzar.jvmSignatureFor(NodeUtil.getParamType(x),
                                                returnType, component.getName());

        String mname;

        // TODO different collision rules for top-level and for
        // methods. (choice of mname)

        if (selfIndex != NO_SELF) {
            sig = Naming.removeNthSigParameter(sig, selfIndex);
            mname = fmDottedName(singleName(name), selfIndex);
        } else {
            mname = nonCollidingSingleName(name, sig,""); // static params?
        }

        if (!savedInAnObject) {
            // trait default OR top level.
            // DO NOT special case run() here and make it non-static
            // (that used to happen), as that's wrong. It's
            // addressed in the executable wrapper code instead.
            modifiers |= Opcodes.ACC_STATIC;
        }

        CodeGen cg = new CodeGen(this);
        cg.generateActualMethodCode(modifiers, mname, sig, params, selfIndex,
                                    inAMethod, body);

        generateAllWrappersForFn(x, params, sig, modifiers, mname);
    }


    /**
     * @param x
     * @param params
     * @param selfIndex
     * @param sig
     * @param modifiers
     * @param mname
     */
    private void generateAllWrappersForFn(FnDecl x, List<Param> params,
            String sig, int modifiers,
            String mname) {
        CodeGen cg = new CodeGen(this);
        /* This code generates forwarding wrappers for
         * the (local) unambiguous name of the function.
         */

    }


    /**
     * @param params
     * @param sig
     * @param modifiers
     * @param mname
     * @param cg
     * @param sf
     */
    private void generateUnambiguousWrappersForApi() {
    }

    /** Generate an actual Java method and body code from an Expr.
     *  Should be done within a nested codegen as follows:
     *  new CodeGen(this).generateActualMethodCode(...);
     */
    private void generateWrapperMethodCode(int modifiers, String mname, String wname, String sig,
                                          List<Param> params) {

        // ignore virtual, now.
        if (0 == (Opcodes.ACC_STATIC & modifiers))
            return;

        mv = cw.visitCGMethod(modifiers, wname, sig, null, null);
        mv.visitCode();

        // Need to copy the parameter across for the wrapper call.
        // Modifiers should tell us how to do the call, maybe?
        // Invokestatic, for now.

        int i = 0;

        for (Param p : params) {
            // Type ty = p.getIdType().unwrap();
            mv.visitVarInsn(Opcodes.ALOAD, i);
            i++;
        }

        mv.visitMethodInsn(Opcodes.INVOKESTATIC, packageAndClassName, mname, sig);

        methodReturnAndFinish();
    }

    /** Generate an actual Java method and body code from an Expr.
     *  Should be done within a nested codegen as follows:
     *  new CodeGen(this).generateActualMethodCode(...);
     */
    private void generateActualMethodCode(int modifiers, String mname, String sig,
                                          List<Param> params, int selfIndex,
                                          boolean inAMethod, Expr body) {

        mv = cw.visitCGMethod(modifiers, mname, sig, null, null);
        mv.visitCode();

        // Now inside method body. Generate code for the method
        // body. Start by binding the parameters and setting up the
        // initial locals.
        VarCodeGen selfVar = null;
        if (inAMethod) {
            selfVar = addSelf();
        }
        List<VarCodeGen> paramsGen = new ArrayList<VarCodeGen>(params.size());
        int index = 0;
        for (Param p : params) {
            if (index != selfIndex) {
                VarCodeGen v = addParam(p);
                paramsGen.add(v);
            }
            index++;
        }
        // Compile the body in the parameter environment

        body.accept(this);
        try {
            exitMethodScope(selfIndex, selfVar, paramsGen);
        } catch (Throwable t) {
            throw new Error("\n"+NodeUtil.getSpan(body)+": Error trying to close method scope.",t);
        }
    }

    private static final Modifiers fnDeclCompilableModifiers =
        Modifiers.GetterSetter.combine(Modifiers.IO).combine(
            Modifiers.Private).combine(Modifiers.Abstract);

    public void forFnDecl(FnDecl x) {
        /*
         * Cases for FnDecl:
         *
         * 1. top level
         *
         * 2. trait normal method. a. for trait itself, an abstract method in
         * generated interface (this may be handled elsewhere) b. for trait
         * defaults, a static method in SpringBoard
         *
         * 3. trait functional method a. for trait itself, a mangled-name
         * abstract method with self removed from the parameter list. (this may
         * be handled elsewhere) b. at top level, a functional wrapper with self
         * in original position, which invokes the interface method with self in
         * dotted position. NOTE THE POTENTIAL FOR OVERLOADING. c. for trait
         * defaults, a mangled-name static method with self in the first
         * parameter position (in SpringBoard).
         *
         * 4. object normal method a. a normal dotted method is generated
         *
         * 5. object functional method a. a mangled-name dotted method is
         * generated with self removed from the parameter list. b. at top level,
         * a functional wrapper with self in original position, which invokes
         * the interface method with self in dotted position. NOTE THE POTENTIAL
         * FOR OVERLOADING. Static overload resolution can be an optimization.
         */

        debug("forFnDecl ", x);
        FnHeader header = x.getHeader();

        List<Param> params = header.getParams();
        int selfIndex = selfParameterIndex(params);

        // Someone had better get rid of anonymous names before they get to CG.
        IdOrOp name = (IdOrOp) header.getName();
        IdOrOp uaname = (IdOrOp) x.getUnambiguousName();

        if (emittingFunctionalMethodWrappers) {
            if (selfIndex==NO_SELF)
                return; // Not functional = no wrapper needed.
        }

        Option<com.sun.fortress.nodes.Type> optReturnType = header.getReturnType();

        if (optReturnType.isNone())
            sayWhat(x, "Return type is not inferred.");

        com.sun.fortress.nodes.Type returnType = optReturnType.unwrap();

        if (name instanceof Id) {
            Id id = (Id) name;
            debug("forId ", id, " class = ", NamingCzar.jvmClassForSymbol(id));
        } else if (name instanceof Op) {
            Op op = (Op) name;
            Fixity fixity = op.getFixity();
            boolean isEnclosing = op.isEnclosing();
            Option<APIName> maybe_apiName = op.getApiName();
            debug("forOp ", op, " fixity = ", fixity, " isEnclosing = ",
                    isEnclosing, " class = ", NamingCzar.jvmClassForSymbol(op));
        } else {
            sayWhat(x, "Unhandled function name.");
        }

        List<StaticParam> sparams = header.getStaticParams();

        boolean canCompile =
            
        header.getWhereClause().isNone() && // no where clause
        header.getThrowsClause().isNone() && // no throws clause
        header.getContract().isNone() && // no contract
        fnDeclCompilableModifiers.containsAll(header.getMods()) && // no unhandled modifiers
        !inABlock; // no local functions

        if (!canCompile)
            sayWhat(x, "Don't know how to compile this kind of FnDecl.");

        boolean inAMethod = inAnObject || inATrait;
        boolean savedInAnObject = inAnObject;
        boolean savedInATrait = inATrait;
        boolean savedEmittingFunctionalMethodWrappers = emittingFunctionalMethodWrappers;

        try {
            inAnObject = false;
            inATrait = false;

            if (emittingFunctionalMethodWrappers) {
                if (! sparams.isEmpty()) {
                    sayWhat(x, "Generic functional methods not yet implemented.");
                } else {
                    functionalMethodWrapper(x, (IdOrOp)name,  selfIndex, savedInATrait);
                }
            } else {

                Option<Expr> optBody = x.getBody();
                if (optBody.isNone()) {
                    if (savedInATrait) return; // Nothing concrete to do; dumpSigs already generated abstract signature.
                    sayWhat(x, "Abstract function declarations are only supported in traits.");
                }
                Expr body = optBody.unwrap();

                // For now every Fortress entity is made public, with
                // namespace management happening in Fortress-land. Right?
                // [JWM:] we'll want to clamp down on this long-term, but
                // we have to get nesting right---we generate a pile of
                // class files for one Fortress component

                if (! sparams.isEmpty()) {
                    if (inAMethod) {
                        // A generic method in a trait or object.

                        generateGenericMethod(x, (IdOrOp)name,
                                sparams, params, selfIndex, savedInATrait, returnType, inAMethod, body);
                        // sayWhat(x, "Generic methods not yet implemented.");

                    } else {
                        generateGenericFunctionClass(x, (IdOrOp)name, selfIndex);
                    }
                 } else if (savedInATrait) {
                    generateTraitDefaultMethod(x, (IdOrOp)name,
                            params, selfIndex, returnType, inAMethod, body);
                } else {
                    generateFunctionalBody(x, (IdOrOp)name,
                            params, selfIndex, returnType, inAMethod,
                            savedInAnObject, body);
                }
            }

        } finally {
            inAnObject = savedInAnObject;
            inATrait = savedInATrait;
            emittingFunctionalMethodWrappers = savedEmittingFunctionalMethodWrappers;
        }

    }

    private ArrowType fndeclToType(FnDecl x) {
        FnHeader fh = x.getHeader();
        Type rt = fh.getReturnType().unwrap();
        List<Param> lp = fh.getParams();
        return typeAndParamsToArrow(x, rt, lp);
    }


    /**
     * @param x
     * @param rt
     * @param lp
     * @return
     */
    private ArrowType typeAndParamsToArrow(Decl x, Type rt, List<Param> lp) {
        Type dt = null;
        switch (lp.size()) {
        case 0:
            dt = NodeFactory.makeVoidType(x.getInfo().getSpan());
            break;
        case 1:
            dt = (Type)lp.get(0).getIdType().unwrap(); // TODO varargs
            break;
        default:
            dt = NodeFactory.makeTupleType(Useful.applyToAll(lp, new Fn<Param,Type>() {
                @Override
                public Type apply(Param x) {
                    return (Type)x.getIdType().unwrap(); // TODO varargs
                }}));
            break;
        }
        return NodeFactory.makeArrowType(NodeFactory.makeSpan(dt,rt), dt, rt);
    }


    private String genericDecoration(FnDecl x, Map<String, String> xlation, List<String> splist) {
        List<StaticParam> sparams = x.getHeader().getStaticParams();
        return NamingCzar.genericDecoration(sparams, xlation, splist, thisApi());
    }



    /**
     * @param selfIndex
     * @param cg
     * @param selfVar
     * @param paramsGen
     */
    private void exitMethodScope(int selfIndex, VarCodeGen selfVar, List<VarCodeGen> paramsGen) {
        for (int i = paramsGen.size() - 1; i >= 0; i--) {
            if (i != selfIndex) {
                VarCodeGen v = paramsGen.get(i);
                v.outOfScope(mv);
            }
        }
        if (selfVar != null)
            selfVar.outOfScope(mv);

        methodReturnAndFinish();
    }

    /**
     * @param x
     * @param params
     * @param selfIndex
     * @param name
     * @param savedInATrait
     * @param returnType
     */
    private void functionalMethodWrapper(FnDecl x, IdOrOp name,
                                         int selfIndex,
                                         boolean savedInATrait) {
        // FnHeader header = x.getHeader();
        TraitTypeHeader trait_header = currentTraitObjectDecl.getHeader();
        List<StaticParam> trait_sparams = trait_header.getStaticParams();
        String uaname = NamingCzar.idOrOpToString(x.getUnambiguousName());

        String dottedName = fmDottedName(singleName(name), selfIndex);

        int invocation = savedInATrait ? INVOKEINTERFACE : INVOKEVIRTUAL;

        if (trait_sparams.size() == 0) {
            int modifiers = Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC;

            FnHeader header = x.getHeader();
            List<Param> params = header.getParams();
            com.sun.fortress.nodes.Type returnType = header.getReturnType()
                    .unwrap();
            String sig = NamingCzar.jvmSignatureFor(NodeUtil.getParamType(x),
                    returnType, component.getName());

            // TODO different collision rules for top-level and for methods.
            String mname = nonCollidingSingleName(name, sig, "");

            InstantiatingClassloader.forwardingMethod(cw, mname, modifiers,
                    selfIndex, traitOrObjectName, dottedName, invocation, sig, sig,
                    params.size(), true);

        } else {
            int modifiers = Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC;

            // Generic case -- rewrite declaration.
            Map<String, String> xlation = new HashMap<String, String>();
            ArrayList<String> splist = new ArrayList<String>();
            String sparams_part = NamingCzar.genericDecoration(trait_sparams,
                    xlation, splist, thisApi());
            FnDecl y = x;
            // NO // x = (FnDecl) x.accept(new GenericNumberer(xlation));

            // Get translated bits.
            FnHeader header = x.getHeader();
            List<Param> params = header.getParams();
            com.sun.fortress.nodes.Type returnType = header.getReturnType()
                    .unwrap();
            String sig = NamingCzar.jvmSignatureFor(NodeUtil.getParamType(x),
                    returnType, component.getName());

            ArrowType at = fndeclToType(y); // type schema from old
            String generic_arrow_type = NamingCzar.jvmTypeDesc(at, thisApi(),
                       false);

            String mname = nonCollidingSingleName(name, sig, generic_arrow_type);

            functionalMethodOfGenericTraitObjectWrapper(mname, sparams_part,
                    sig, generic_arrow_type, invocation, dottedName, selfIndex,
                    params, modifiers, splist);

        }

    }


    /**
     * @param mname
     * @param sparams_part
     * @param sig
     * @param generic_arrow_type
     * @param invocation
     * @param dottedName
     * @param selfIndex
     * @param params
     * @param modifiers
     */
    private void functionalMethodOfGenericTraitObjectWrapper(String mname,
            String sparams_part, String sig, String generic_arrow_type,
            int invocation, String dottedName, int selfIndex,
            List<Param> params, int modifiers, List<String> splist) {
        String PCN =
            Naming.genericFunctionPkgClass(packageAndClassName, mname,
                                               sparams_part, generic_arrow_type);
        String PCNOuter =
            Naming.genericFunctionPkgClass(packageAndClassName, mname,
                        makeTemplateSParams(sparams_part) , generic_arrow_type);
        // System.err.println(PCN);

        CodeGen cg = new CodeGen(this);
        cg.cw = new CodeGenClassWriter(ClassWriter.COMPUTE_FRAMES, cw);

        // This creates the closure bits
        InstantiatingClassloader.closureClassPrefix(PCN, cg.cw, PCN, sig);

        InstantiatingClassloader.forwardingMethod(cg.cw, mname, modifiers,
                selfIndex,
                //traitOrObjectName+sparams_part,
                traitOrObjectName,
                dottedName, invocation, sig, sig,
                params.size(), true);

        cg.cw.dumpClass(PCNOuter, splist);
    }

    /**
     * @param cg
     */

    private void methodReturnAndFinish() {
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(NamingCzar.ignore, NamingCzar.ignore);
        mv.visitEnd();
    }

    /**
     * TODO: surely this is derivable from the arrow type, which maintains the selfParameterIndex?
     * Are those inconsistent with one another?
     * @param params
     * @return
     */
    private int selfParameterIndex(List<Param> params) {
        int selfIndex = NO_SELF;
        int i = 0;
        for (Param p : params) {
            if (p.getName().getText() == "self") {
                selfIndex = i;
                break;
            }
            i++;
        }
        return selfIndex;
    }

    public void forFnExpr(FnExpr x) {
        debug("forFnExpr ", x);
        FnHeader header = x.getHeader();
        Expr body = x.getBody();
        List<Param> params = header.getParams();
        Option<Type> returnType = header.getReturnType();
        if (!returnType.isSome())
            throw new CompilerError(x, "No return type");
        Type rt = returnType.unwrap();


        //      Create the Class
        String desc = NamingCzar.makeAbstractArrowDescriptor(params, rt, thisApi());
        String idesc = NamingCzar.makeArrowDescriptor(params, rt, thisApi());
        CodeGen cg = new CodeGen(this);
        cg.cw = new CodeGenClassWriter(ClassWriter.COMPUTE_FRAMES, cw);
        cg.cw.visitSource(NodeUtil.getSpan(x).begin.getFileName(), null);

        String className = NamingCzar.gensymArrowClassName(Naming.deDot(thisApi().getText()));

        debug("forFnExpr className = ", className, " desc = ", desc);
        List<VarCodeGen> freeVars = getFreeVars(body);
        cg.lexEnv = cg.createTaskLexEnvVariables(className, freeVars);
        cg.cw.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER,
                    className, null, desc, new String[] {idesc});

        // Generate the constructor (initializes captured free vars from param list)
        String init = taskConstructorDesc(freeVars);
        cg.generateTaskInit(desc, init, freeVars);

        String applyDesc = NamingCzar.jvmSignatureFor(params, NamingCzar.jvmTypeDesc(rt, thisApi()),
                                                      thisApi());

        // Generate the apply method
        // System.err.println(idesc+".apply"+applyDesc+" gen in "+className);
        cg.mv = cg.cw.visitCGMethod(Opcodes.ACC_PUBLIC, Naming.APPLY_METHOD, applyDesc, null, null);
        cg.mv.visitCode();

        // Since we call this virtually we need a slot for the arrow implementation of this object.
        cg.mv.reserveSlot0();
        for (Param p : params) {
            cg.addParam(p);
        }

        body.accept(cg);

        cg.methodReturnAndFinish();
        cg.cw.dumpClass(className);

        constructWithFreeVars(className, freeVars, init);
    }


    /**
     * Creates a name that will not collide with any overloaded functions
     * (the overloaded name "wins" because if it is exported, this one is not).
     *
     * @param name
     * @param sig The jvm signature for a method, e.g., (ILjava/lang/Object;)D
     * @return
     */
    private String nonCollidingSingleName(IdOrOpOrAnonymousName name, String sig, String schema) {
        String mname = singleName(name);
        if (overloadedNamesAndSigs.contains(mname+sig+schema)) {
            mname = NamingCzar.mangleAwayFromOverload(mname);
        }
        return mname;
    }

    /**
     * Method name, with symbolic-freedom-mangling applied
     *
     * @param name
     * @return
     */
    private String singleName(IdOrOpOrAnonymousName name) {
        String nameString = NamingCzar.idOrOpToString((IdOrOp)name);
        String mname = nameString; // Naming.mangleIdentifier(nameString);
        return mname;
    }

    // belongs in Naming perhaps
    private static String fmDottedName(String name, int selfIndex) {
        // HACK.  Need to be able to express some fmDotteds in Java source code
        // thus, must transmogrify everything that is not A-Z to something else.

//        if (!isBoring(name)) {
//            name = makeBoring(name);
//        }
        //
        name = name + Naming.INDEX + selfIndex;
        return name;
    }


    private static boolean isBoring(String name) {
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (i == 0 ? Character.isJavaIdentifierStart(ch) : Character.isJavaIdentifierPart(ch))
                continue;
            return false;
        }
        return true;
    }

    private static String makeBoring(String name) {
        StringBuffer b = new StringBuffer();

        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (i == 0 ? Character.isJavaIdentifierStart(ch) : Character.isJavaIdentifierPart(ch)) {
                b.append(ch);
            } else {
                b.append('x');
                b. append(Integer.toHexString(ch));
            }
        }
        return b.toString();
    }

    // Setting up the alias table which we will refer to at runtime.
    public void forFnRef(FnRef x) {
        debug("forFnRef ", x);
        if (fnRefIsApply) {
            forFunctionalRef(x);
            return;
        }

        // Not entirely sure about this next bit; how are function-valued parameters referenced?
        VarCodeGen fn = getLocalVarOrNull(x.getOriginalName());
        if (fn != null) {
            sayWhat(x, "Haven't figured out references to local/parameter functions yet");
            return;
        }

        // need to deal with generics.
        List<StaticArg> sargs = x.getStaticArgs();

        // Get it from top level.
        Pair<String, String> pc_and_m= functionalRefToPackageClassAndMethod(x);
        // If it's an overloaded type, oy.
        com.sun.fortress.nodes.Type arrow = exprType(x);
        // Capture the overloading foo, mutilate that into the name of the thing that we want.
        Pair<String, String> method_and_signature = resolveMethodAndSignature(
                x, arrow, pc_and_m.second());
        /* we now have package+class, method name, and signature.
         * Emit a static reference to a field in package/class/method+envelope+mangled_sig.
         * Classloader will see this, and it will trigger demangling of the name, to figure
         * out the contents of the class to be loaded.
         */
        String arrow_desc = NamingCzar.jvmTypeDesc(arrow, thisApi(), true);
        String arrow_type = NamingCzar.jvmTypeDesc(arrow, thisApi(), false);
        String PCN = pc_and_m.first() + "$" +

            method_and_signature.first() +
            Naming.ENVELOPE + "$"+ // "ENVELOPE"
            arrow_type;
        /* The suffix will be
         * (potentially mangled)
         * functionName<ENVELOPE>closureType (which is an arrow)
         *
         * must generate code for the class with a method apply, that
         * INVOKE_STATICs prefix.functionName .
         */
        mv.visitFieldInsn(Opcodes.GETSTATIC, PCN, NamingCzar.closureFieldName, arrow_desc);
    }

    /**
     * @param x
     */
    public void forFunctionalRef(FunctionalRef x) {
        debug("forFunctionalRef ", x);

        /* Arrow, or perhaps an intersection if it is an overloaded function. */
        com.sun.fortress.nodes.Type arrow = exprType(x);

        List<StaticArg> sargs = x.getStaticArgs();
        if (arrow instanceof ArrowType) {
            /*
             *  Note this does not yet deal with functional, generic methods
             *  because it stomps on "sargs".
             */
            Option<MethodInfo> omi = ((ArrowType) arrow).getMethodInfo();
            if (omi.isSome()) {
                MethodInfo mi = omi.unwrap();
                int self_i = mi.getSelfPosition();
                Type self_t = mi.getSelfType();
                if (self_t instanceof TraitSelfType)
                    self_t = ((TraitSelfType)self_t).getNamed();
                if (self_t instanceof TraitType) {
                    sargs = ((TraitType) self_t).getArgs();
                }
            }
        } else {
            System.err.println("non-arrowtype "+arrow);
        }

        String decoration = NamingCzar.genericDecoration(sargs, thisApi());


        debug("forFunctionalRef ", x, " arrow = ", arrow);

        Pair<String, String> calleeInfo = functionalRefToPackageClassAndMethod(x);

        String pkgClass = calleeInfo.first();

        if (decoration.length() > 0) {
            // debugging reexecute
            decoration = NamingCzar.genericDecoration(sargs, thisApi());
            /*
             * TODO, BUG, need to use arrow type of uninstantiated generic!
             * This is necessary because otherwise it is difficult (impossible?)
             * to figure out the name of the template class that will be
             * expanded later.
             */

            Option<Type> oschema = x.getOverloadingSchema();
            Type arrowToUse = arrow;

            if (oschema.isSome()) {
                arrowToUse = oschema.unwrap();
            } else {
                System.err.println(NodeUtil.getSpan(x) + ": FunctionalRef " + x + " lacks overloading schema; using "+arrowToUse+" sargs "+sargs);
            }

            String arrow_type = NamingCzar.jvmTypeDesc(arrowToUse, thisApi(), false);

            pkgClass =
                Naming.genericFunctionPkgClass(pkgClass, calleeInfo.second(),
                                                   decoration, arrow_type);

            // pkgClass = pkgClass.replace(".", "/");
            // DEBUG, for looking at the schema append to a reference.
            // System.err.println("At " + x.getInfo().getSpan() + ", " + pkgClass);
        }

        callStaticSingleOrOverloaded(x, arrow, pkgClass, calleeInfo.second());
    }

    /**
     * @param x
     * @return
     */
    private Pair<String, String> functionalRefToPackageClassAndMethod(
            FunctionalRef x) {
        List<IdOrOp> names = x.getNames();

        if ( names.size() != 1) {
            return sayWhat(x,"Non-unique overloading after rewrite " + x);
        }

        IdOrOp n = names.get(0);

        if (n.getText().contains(Naming.FOREIGN_TAG))
            return idToPackageClassAndName(n);

        Option<APIName> oapi = n.getApiName();
        if (oapi.isSome()) {
            APIName a = oapi.unwrap();
            ApiIndex ai = env.apis().get(a);
            Relation<IdOrOpOrAnonymousName, Function> fns = ai.functions();
            Set<Function> s = fns.matchFirst(NodeFactory.makeLocalIdOrOp(n));
            if (s.size() != 0) {
                IdOrOp nn = NodeFactory.makeIdOrOp(a, x.getOriginalName());
                Pair<String, String> trial = idToPackageClassAndName(nn);
                Pair<String, String> rval = idToPackageClassAndName(n);
//                if (! (trial.first().equals(rval.first()) &&
//                       trial.second().equals(rval.second()))) {
//                    System.err.println("Substitute " +
//                            trial.first()+"."+trial.second()+" for "+
//                            rval.first()+"."+rval.second());
//                }
                return trial;

            } else {
                Pair<String, String> rval = idToPackageClassAndName(n);
                return rval;

            }

        } else {
            Relation<IdOrOpOrAnonymousName, Function> fns = ci.functions();
            Set<Function> s = fns.matchFirst(n);
            if (s.size() == 1) {
                IdOrOp nn = x.getOriginalName();
                Pair<String, String> trial = idToPackageClassAndName(nn);
                Pair<String, String> rval = idToPackageClassAndName(n);
//                if (!(trial.first().equals(rval.first()) && trial.second()
//                        .equals(rval.second()))) {
//                    System.err.println("Substitute " + trial.first() + "."
//                            + trial.second() + " for " + rval.first() + "."
//                            + rval.second());
//                }
                return trial;
            } else {
                IdOrOp nn = x.getOriginalName();
                Pair<String, String> trial = idToPackageClassAndName(nn);
                Pair<String, String> rval = idToPackageClassAndName(n);
                return rval;
            }

        }
        // return idToPackageClassAndName(n); // names.get(0));
    }

    private Pair<String, String> idToPackageClassAndName(IdOrOp fnName) {
        return NamingCzar.idToPackageClassAndName(fnName, thisApi());
    }
    public void forIf(If x) {
        Debug.debug( Debug.Type.CODEGEN, 1,"forIf ", x);
        List<IfClause> clauses = x.getClauses();
        Option<Block> elseClause = x.getElseClause();

        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        org.objectweb.asm.Label falseBranch = new org.objectweb.asm.Label();
        for (IfClause ifclause : clauses) {

            GeneratorClause cond = ifclause.getTestClause();

            if (!cond.getBind().isEmpty())
                sayWhat(x, "Undesugared generalized if expression.");

            // emit code for condition and to check resulting boolean
            Expr testExpr = cond.getInit();
            debug( "about to accept ", testExpr, " of class ", testExpr.getClass());
            testExpr.accept(this);
            addLineNumberInfo(x);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                               NamingCzar.internalFortressBoolean, "getValue",
                               NamingCzar.makeMethodDesc("", NamingCzar.descBoolean));
            mv.visitJumpInsn(Opcodes.IFEQ, falseBranch);

            // emit code for condition true
            ifclause.getBody().accept(this);
            mv.visitJumpInsn(Opcodes.GOTO, done);

            // control goes to following label if condition false (and we continue tests)
            mv.visitLabel(falseBranch);
            falseBranch = new org.objectweb.asm.Label();
        }
        Option<Block> maybe_else = x.getElseClause();
        if (maybe_else.isSome()) {
            maybe_else.unwrap().accept(this);
        } else {
            pushVoid();
        }
        mv.visitLabel(done);
    }

    public void forImportNames(ImportNames x) {
        // No longer need to set up alias table; rely on ForeignJava exclusively instead.
    }

    public void forIntLiteralExpr(IntLiteralExpr x) {
        debug("forIntLiteral ", x);
        BigInteger bi = x.getIntVal();
        // This might not work.
        int l = bi.bitLength();
        if (l <= 32) {
            int y = bi.intValue();
            addLineNumberInfo(x);
            pushInteger(y);
            addLineNumberInfo(x);

            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    NamingCzar.internalFortressIntLiteral, NamingCzar.make,
                    NamingCzar.makeMethodDesc(NamingCzar.descInt,
                                              NamingCzar.descFortressIntLiteral));
        } else if (l <= 64) {
            long yy = bi.longValue();
            addLineNumberInfo(x);
            mv.visitLdcInsn(yy);
            addLineNumberInfo(x);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    NamingCzar.internalFortressIntLiteral, NamingCzar.make,
                    NamingCzar.makeMethodDesc(NamingCzar.descLong,
                                              NamingCzar.descFortressIntLiteral));
        } else {
            String s = bi.toString();
            addLineNumberInfo(x);
            mv.visitLdcInsn(s);
            addLineNumberInfo(x);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    NamingCzar.internalFortressIntLiteral, NamingCzar.make,
                    NamingCzar.makeMethodDesc(NamingCzar.descString,
                                              NamingCzar.descFortressIntLiteral));
        }
    }

    public void forLocalVarDecl(LocalVarDecl d) {
        debug("forLocalVarDecl", d);
        List<LValue> lhs = d.getLhs();
        if (!d.getRhs().isSome()) {
            // Just a forward declaration to be bound in subsequent
            // code.  But we need to leave a marker so that the
            // definitions down different control flow paths are
            // consistent; basically we need to create the definition
            // here, and the use that VarCodeGen object for the
            // subsequent true definitions.
            sayWhat(d, "Can't yet handle forward binding declarations.");
        }
        int n = lhs.size();
        List<VarCodeGen> vcgs = new ArrayList(n);
        for (LValue v : lhs) {
            if (v.isMutable()) {
                sayWhat(d, "Can't yet generate code for mutable variable declarations.");
            }
            if (!v.getIdType().isSome()) {
                sayWhat(d, "Variable being bound lacks type information!");
            }

            // Introduce variable
            Type ty = (Type)v.getIdType().unwrap();
            VarCodeGen vcg = new VarCodeGen.LocalVar(v.getName(), ty, this);
            vcgs.add(vcg);
        }

        // Compute rhs
        List<Expr> rhss;
        Expr rhs = d.getRhs().unwrap();
        if (n==1) {
            rhss = Collections.singletonList(rhs);
        } else if (rhs instanceof TupleExpr &&
                   !((TupleExpr)rhs).getVarargs().isSome() &&
                   ((TupleExpr)rhs).getKeywords().isEmpty() &&
                   ((TupleExpr)rhs).getExprs().size() == n) {
            rhss = ((TupleExpr)rhs).getExprs();
        } else {
            sayWhat(d, "Can't yet generate multiple-variable bindings unless rhs is a manifest tuple of the same size.");
            return;
        }

        if (false && pa.worthParallelizing(rhs)) {
            forExprsParallel(rhss, vcgs);
        } else {
            forExprsSerial(rhss,vcgs);
        }

        // Evaluate rest of block with binding in scope
        CodeGen cg = new CodeGen(this);
        for (VarCodeGen vcg : vcgs) {
            cg.addLocalVar(vcg);
        }

        cg.doStatements(d.getBody().getExprs());

        // Dispose of bindings now that we're done
        // Do this in reverse order of creation.
        for (int i = n-1; i >= 0; i--) {
            vcgs.get(i).outOfScope(mv);
        }
    }

    // forObjectDecl just generates top-level bindings for functional
    // methods of object decls.  All the remaining work (of actually
    // generating the object class) is done by forObjectDeclPrePass.
    public void forObjectDecl(ObjectDecl x) {
        TraitTypeHeader header = x.getHeader();
        emittingFunctionalMethodWrappers = true;

        // TODO trim and/or consolidate this boilerplate around sparams_part
        Map<String, String> xlation = new HashMap<String, String>();
        List<String> splist = new ArrayList<String>();
        List<StaticParam> original_static_params = header.getStaticParams();
        Option<List<Param>> original_params = NodeUtil.getParams(x);
        String sparams_part = NamingCzar.genericDecoration(original_static_params, xlation, splist, thisApi());

        Id classId = NodeUtil.getName(x);
        String classFile =
            NamingCzar.jvmClassForToplevelTypeDecl(classId,
                    sparams_part,
                    packageAndClassName);

//        String classFile = NamingCzar.makeInnerClassName(packageAndClassName,
//                                                         NamingCzar.idToString(NodeUtil.getName(x)));
        debug("forObjectDecl ",x," classFile = ", classFile);
        traitOrObjectName = classFile;
        currentTraitObjectDecl = x;
        dumpTraitDecls(header.getDecls());
        currentTraitObjectDecl = null;
        emittingFunctionalMethodWrappers = false;
        traitOrObjectName = null;
    }

    // forObjectDeclPrePass actually generates the class corresponding
    // to the given ObjectDecl.
    public void forObjectDeclPrePass(ObjectDecl x) {
        debug("forObjectDeclPrePass ", x);
        TraitTypeHeader header = x.getHeader();
        List<TraitTypeWhere> extendsC = header.getExtendsClause();

        boolean canCompile =
            // x.getParams().isNone() &&             // no parameters
            // header.getStaticParams().isEmpty() && // no static parameter
            header.getWhereClause().isNone() &&   // no where clause
            header.getThrowsClause().isNone() &&  // no throws clause
            header.getContract().isNone() &&      // no contract
            //            header.getDecls().isEmpty() &&        // no members
            Modifiers.ObjectMod.containsAll(header.getMods())
            // ( extendsC.size() <= 1 ); // 0 or 1 super trait
            ;

        if ( !canCompile ) sayWhat(x);

        Map<String, String> xlation = new HashMap<String, String>();
        List<String> splist = new ArrayList<String>();
        List<StaticParam> original_static_params = header.getStaticParams();
        Option<List<Param>> original_params = NodeUtil.getParams(x);
        String sparams_part = NamingCzar.genericDecoration(original_static_params, xlation, splist, thisApi());

        ObjectDecl y = x;
        // Rewrite the generic.
        // need to do more differently if it is a constructor.
        if (sparams_part.length() > 0 ) {

            // NO // x = (ObjectDecl) y.accept(new GenericNumberer(xlation));
            // Refresh these post-rewrite
            header = x.getHeader();
            extendsC = header.getExtendsClause();

        }

        boolean savedInAnObject = inAnObject;
        inAnObject = true;
        Id classId = NodeUtil.getName(x);
        String erasedSuperI = sparams_part.length() > 0 ?
                NamingCzar.jvmClassForToplevelTypeDecl(classId,
                "",
                packageAndClassName) : "";
        String [] superInterfaces =
            NamingCzar.extendsClauseToInterfaces(extendsC, component.getName(), erasedSuperI);

        if (sparams_part.length() > 0) {
            emitErasedClassFor(erasedSuperI, (TraitObjectDecl) x);
        }

        String abstractSuperclass;
        if (superInterfaces.length > 0) {
            abstractSuperclass = superInterfaces[0] + NamingCzar.springBoard;
        } else {
            abstractSuperclass = NamingCzar.internalObject;
        }
        String classFile =
            NamingCzar.jvmClassForToplevelTypeDecl(classId,
                    sparams_part,
                    packageAndClassName);
        String classFileOuter =
            NamingCzar.jvmClassForToplevelTypeDecl(classId,
                    makeTemplateSParams(sparams_part),
                    packageAndClassName);
        String classFileMinusSparams =
            NamingCzar.jvmClassForToplevelTypeDecl(classId,"",packageAndClassName);
        traitOrObjectName = classFile;
        String classDesc = NamingCzar.internalToDesc(classFile);
        debug("forObjectDeclPrePass ",x," classFile = ", classFile);


        boolean hasParameters = NodeUtil.getParams(x).isSome();
        List<Param> params;
        if (hasParameters) {
            params = NodeUtil.getParams(x).unwrap();
            String init_sig = NamingCzar.jvmSignatureFor(params, "V", thisApi());

             // Generate the factory method
            String sig = NamingCzar.jvmSignatureFor(params, classDesc, thisApi());

            String mname ;

            CodeGen cg = this;
            String PCN = null;
            String PCNOuter = null;

            if (sparams_part.length() > 0) {
                ArrowType at =
                    typeAndParamsToArrow(x,
                            NodeFactory.makeTraitType(classId,
                                    STypesUtil.staticParamsToArgs(original_static_params)),
                                    original_params.unwrap());
                String generic_arrow_type = NamingCzar.jvmTypeDesc(at, thisApi(), false);
                mname = nonCollidingSingleName(x.getHeader().getName(), sig, generic_arrow_type);
                PCN =
                    Naming.genericFunctionPkgClass(packageAndClassName, mname,
                                                       sparams_part, generic_arrow_type);
                PCNOuter =
                    Naming.genericFunctionPkgClass(packageAndClassName, mname,
                                makeTemplateSParams(sparams_part) , generic_arrow_type);


                cg = new CodeGen(this);
                cg.cw = new CodeGenClassWriter(ClassWriter.COMPUTE_FRAMES, cw);

                // This creates the closure bits
                InstantiatingClassloader.closureClassPrefix(PCN, cg.cw, PCN, sig);
            } else {
                mname = nonCollidingSingleName(x.getHeader().getName(), sig, "");
            }

            CodeGenClassWriter cw = cg.cw;
            CodeGenMethodVisitor mv = cw.visitCGMethod(Opcodes.ACC_STATIC,
                    mname,
                    sig,
                    null,
                    null);

            mv.visitTypeInsn(Opcodes.NEW, classFile);
            mv.visitInsn(Opcodes.DUP);

            // iterate, pushing parameters, beginning at zero.
           // TODO actually handle N>0 parameters.

            int stack_offset = 0;
            for (Param p : params) {
                // when we unbox, this will be type-dependent
                mv.visitVarInsn(Opcodes.ALOAD, stack_offset);
                stack_offset++;
            }

            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, classFile, "<init>", init_sig);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(NamingCzar.ignore, NamingCzar.ignore);
            mv.visitEnd();

            if (sparams_part.length() > 0) {
                cg.cw.dumpClass(PCNOuter, splist);
            }


        } else { // singleton
            params = Collections.<Param>emptyList();
        }

        CodeGenClassWriter prev = cw;

        /* Yuck, ought to allocate a new codegen here. */
        
        initializedStaticFields_TO = new ArrayList<InitializedStaticField>();
        
        cw = new CodeGenClassWriter(ClassWriter.COMPUTE_FRAMES, cw);
        cw.visitSource(NodeUtil.getSpan(x).begin.getFileName(), null);

        // Until we resolve the directory hierarchy problem.
        //            cw.visit( Opcodes.V1_5, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER+ Opcodes.ACC_FINAL,
        //                      classFile, null, NamingCzar.internalObject, new String[] { parent });
        cw.visit( Opcodes.V1_5, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER + Opcodes.ACC_FINAL,
                  classFile, null, abstractSuperclass, superInterfaces);

        if (!hasParameters) {
            // Singleton; generate field in class to hold sole instance.
            cw.visitField(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC + Opcodes.ACC_FINAL,
                          NamingCzar.SINGLETON_FIELD_NAME, classDesc,
                          null /* for non-generic */, null /* instance has no value */);
        }

        // Emit fields here, one per parameter.
        generateFieldsAndInitMethod(classFile, abstractSuperclass, params);

         BATree<String, VarCodeGen> savedLexEnv = lexEnv.copy();

        // need to add locals to the environment.
        // each one has name, mangled with a preceding "$"
        for (Param p : params) {
            Type param_type = (Type)p.getIdType().unwrap();
            String objectFieldName = p.getName().getText();
            Id id =
               NodeFactory.makeId(NodeUtil.getSpan(p.getName()), objectFieldName);
            addStaticVar(new VarCodeGen.FieldVar(id,
                    param_type,
                    classFile,
                    objectFieldName,
                    NamingCzar.jvmTypeDesc(param_type, component.getName(), true)));
        }

        currentTraitObjectDecl = x;
        for (Decl d : header.getDecls()) {
            // This does not work yet.
            d.accept(this);
        }
        dumpMethodChaining(superInterfaces, false);
        dumpErasedMethodChaining(superInterfaces, false);

        lexEnv = savedLexEnv;

        if (!hasParameters || initializedStaticFields_TO.size() > 0) {
            
            MethodVisitor imv = cw.visitMethod(Opcodes.ACC_STATIC,
                                               "<clinit>",
                                               NamingCzar.voidToVoid,
                                               null,
                                               null);

            if (! hasParameters) {
                imv.visitTypeInsn(Opcodes.NEW, classFile);
                imv.visitInsn(Opcodes.DUP);
                imv.visitMethodInsn(Opcodes.INVOKESPECIAL, classFile,
                        "<init>", NamingCzar.voidToVoid);
                imv.visitFieldInsn(Opcodes.PUTSTATIC, classFile,
                        NamingCzar.SINGLETON_FIELD_NAME, classDesc);
 
                addStaticVar(new VarCodeGen.StaticBinding(
                        classId, NodeFactory.makeTraitType(classId),
                        classFileMinusSparams,
                        NamingCzar.SINGLETON_FIELD_NAME, classDesc,
                        splist));
            }
            
            for (InitializedStaticField isf : initializedStaticFields_TO) {
                isf.forClinit(imv);
                cw.visitField(
                        Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC + Opcodes.ACC_FINAL,
                        isf.asmName(), isf.asmSignature(),
                        null /* for non-generic */, null /* instance has no value */);
                // DRC-WIP
            }
            
            imv.visitInsn(Opcodes.RETURN);
            imv.visitMaxs(NamingCzar.ignore, NamingCzar.ignore);
            imv.visitEnd();

        }
        
        if (sparams_part.length() > 0) {
            cw.dumpClass( classFileOuter, splist );
        } else {
            cw.dumpClass( classFile );
        }
        cw = prev;
        initializedStaticFields_TO = null;
        currentTraitObjectDecl = null;
        
        traitOrObjectName = null;

        inAnObject = savedInAnObject;
    }


    private void emitErasedClassFor(String erasedSuperI, TraitObjectDecl x) {
        Id classId = NodeUtil.getName(x);
        String classFile =
            NamingCzar.jvmClassForToplevelTypeDecl(classId, "",
                                                   packageAndClassName);
        String classFileOuter =
            NamingCzar.jvmClassForToplevelTypeDecl(classId, "",
                                                   packageAndClassName);

        traitOrObjectName = classFile;
        String classDesc = NamingCzar.internalToDesc(classFile);

        // need to adapt this code

        // need to include erased superinterfaces
        // need to

        String[] superInterfaces = new String[0];

        CodeGenClassWriter prev = cw;
        cw = new CodeGenClassWriter(ClassWriter.COMPUTE_FRAMES, cw);
        cw.visitSource(NodeUtil.getSpan(x).begin.getFileName(), null);
        cw.visit( Opcodes.V1_5,
                  Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                  classFile, null, NamingCzar.internalObject, superInterfaces);

        cw.dumpClass( classFileOuter );

        cw = prev;



    }


    private String makeTemplateSParams(String sparamsPart) {
        if (sparamsPart.length() == 0)
            return "";
        else
            return Naming.LEFT_OXFORD + Naming.RIGHT_OXFORD;
    }

    // This returns a list rather than a set because the order matters;
    // we should guarantee that we choose a consistent order every time.
    private List<VarCodeGen> getFreeVars(Node n) {
        BASet<IdOrOp> allFvs = fv.freeVars(n);
        List<VarCodeGen> vcgs = new ArrayList<VarCodeGen>();
        if (allFvs == null)
            return sayWhat((ASTNode)n," null free variable information!");
        else {
            for (IdOrOp v : allFvs) {
                VarCodeGen vcg = getLocalVarOrNull(v);
                if (vcg != null) vcgs.add(vcg);
            }
            return vcgs;
        }
    }

    private BATree<String, VarCodeGen>
            createTaskLexEnvVariables(String taskClass, List<VarCodeGen> freeVars) {

        BATree<String, VarCodeGen> result =
            new BATree<String, VarCodeGen>(StringHashComparer.V);
        for (VarCodeGen v : freeVars) {
            String name = v.name.getText();
            cw.visitField(Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL, name,
                          NamingCzar.jvmTypeDesc(v.fortressType, thisApi()),
                          null, null);
            result.put(name, new TaskVarCodeGen(v, taskClass, thisApi()));
        }
        return result;
    }

    private void generateTaskInit(String baseClass,
                                  String initDesc,
                                  List<VarCodeGen> freeVars) {

        mv = cw.visitCGMethod(Opcodes.ACC_PUBLIC, "<init>", initDesc, null, null);
        mv.visitCode();

        // Call superclass constructor
        mv.visitVarInsn(Opcodes.ALOAD, mv.getThis());
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, baseClass,
                              "<init>", NamingCzar.voidToVoid);
        // mv.visitVarInsn(Opcodes.ALOAD, mv.getThis());

        // Stash away free variables Warning: freeVars contains
        // VarCodeGen objects from the parent context, we must look
        // these up again in the child context or we'll get incorrect
        // code (or more usually the compiler will complain).
        int varIndex = 1;
        for (VarCodeGen v0 : freeVars) {
            VarCodeGen v = lexEnv.get(v0.name.getText());
            v.prepareAssignValue(mv);
            mv.visitVarInsn(Opcodes.ALOAD, varIndex++);
            v.assignValue(mv);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(NamingCzar.ignore, NamingCzar.ignore);
        mv.visitEnd();
    }

    private void generateTaskCompute(String className, Expr x, String result) {
        mv = cw.visitCGMethod(Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL,
                                  "compute", "()V", null, null);
        mv.visitCode();

        mv.visitVarInsn(Opcodes.ALOAD, mv.getThis());

        x.accept(this);

        mv.visitFieldInsn(Opcodes.PUTFIELD, className, "result", result);

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(NamingCzar.ignore, NamingCzar.ignore);
        mv.visitEnd();
    }

    // I'm just a stub.  Someday I'll have a body that updates the changed local variables.
    private BATree<String, VarCodeGen> restoreFromTaskLexEnv(BATree<String,VarCodeGen> old, BATree<String,VarCodeGen> task) {
        return task;
    }

    public String taskConstructorDesc(List<VarCodeGen> freeVars) {
        // And their types
        List<Type> freeVarTypes = new ArrayList(freeVars.size());
        for (VarCodeGen v : freeVars) {
            freeVarTypes.add(v.fortressType);
        }
        return NamingCzar.jvmTypeDescForGeneratedTaskInit(freeVarTypes, component.getName());
    }

    // This sets up the parallel task construct.
    // Caveat: We create separate taskClasses for every task
    public String delegate(Expr x, String result, String init, List<VarCodeGen> freeVars) {

        String className = NamingCzar.gensymTaskName(packageAndClassName);

        debug("delegate creating class ", className, " node = ", x,
              " constructor type = ", init, " result type = ", result);

        // Create a new environment, and codegen task class in it.
        CodeGen cg = new CodeGen(this);
        cg.cw = new CodeGenClassWriter(ClassWriter.COMPUTE_FRAMES, cw);
        cg.cw.visitSource(NodeUtil.getSpan(x).begin.getFileName(), null);

        cg.lexEnv = cg.createTaskLexEnvVariables(className, freeVars);
        // WARNING: result may need mangling / NamingCzar-ing.
        cg.cw.visitField(Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL, "result", result, null, null);

        cg.cw.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER + Opcodes.ACC_FINAL,
                    className, null, NamingCzar.fortressBaseTask, null);

        cg.generateTaskInit(NamingCzar.fortressBaseTask, init, freeVars);

        cg.generateTaskCompute(className, x, result);

        cg.cw.dumpClass(className);

        this.lexEnv = restoreFromTaskLexEnv(cg.lexEnv, this.lexEnv);
        return className;
    }

    public void constructWithFreeVars(String cname, List<VarCodeGen> freeVars, String sig) {
            mv.visitTypeInsn(Opcodes.NEW, cname);
            mv.visitInsn(Opcodes.DUP);
            // Push the free variables in order.
            for (VarCodeGen v : freeVars) {
                v.pushValue(mv, "");
            }
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, cname, "<init>", sig);
    }

    // Evaluate args in parallel.  (Why is profitability given at a
    // level where we can't ask the qustion here?)
    // Leave the results (in the order given) on the stack when vcgs==null;
    // otherwise use the provided vcgs to bind corresponding values.
    public void forExprsParallel(List<? extends Expr> args, List<VarCodeGen> vcgs) {
        final int n = args.size();
        if (n <= 0) return;
        String [] tasks = new String[n];
        String [] results = new String[n];
        int [] taskVars = new int[n];

        if (vcgs != null && vcgs.size() != n) {
            sayWhat(args.get(0), "Internal error: number of args does not match number of consumers.");
        }

        // Push arg tasks from right to left, so
        // that local evaluation of args will proceed left to right.
        // IMPORTANT: ALWAYS fork and join stack fashion,
        // ie always join with the most recent fork first.
        for (int i = n-1; i > 0; i--) {
            Expr arg = args.get(i);
            // Make sure arg has type info (we'll need it to generate task)
            Option<Type> ot = NodeUtil.getExprType(arg);
            if (!ot.isSome())
                sayWhat(arg, "Missing type information for argument " + arg);
            Type t = ot.unwrap();
            String tDesc = NamingCzar.jvmTypeDesc(t, component.getName());
            // Find free vars of arg
            List<VarCodeGen> freeVars = getFreeVars(arg);

            // Generate descriptor for init method of task
            String init = taskConstructorDesc(freeVars);

            String task = delegate(arg, tDesc, init, freeVars);
            tasks[i] = task;
            results[i] = tDesc;

            constructWithFreeVars(task, freeVars, init);

            mv.visitInsn(Opcodes.DUP);
            int taskVar = mv.createCompilerLocal(task, // Naming.mangleIdentifier(task),
                    NamingCzar.internalToDesc(task));
            taskVars[i] = taskVar;
            mv.visitVarInsn(Opcodes.ASTORE, taskVar);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, task, "forkIfProfitable", "()V");
        }
        // arg 0 gets compiled in place, rather than turned into work.
        if (vcgs != null) vcgs.get(0).prepareAssignValue(mv);
        args.get(0).accept(this);
        if (vcgs != null) vcgs.get(0).assignValue(mv);

        // join / perform work locally left to right, leaving results on stack.
        for (int i = 1; i < n; i++) {
            if (vcgs != null) vcgs.get(i).prepareAssignValue(mv);
            int taskVar = taskVars[i];
            mv.visitVarInsn(Opcodes.ALOAD, taskVar);
            mv.disposeCompilerLocal(taskVar);
            mv.visitInsn(Opcodes.DUP);
            String task = tasks[i];
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, task, "joinOrRun", "()V");
            mv.visitFieldInsn(Opcodes.GETFIELD, task, "result", results[i]);
            if (vcgs != null) vcgs.get(i).assignValue(mv);
        }
    }

    // Evaluate args serially, from left to right.
    // Leave the results (in the order given) on the stack when vcgs==null;
    // otherwise use the provided vcgs to bind corresponding values.
    public void forExprsSerial(List<? extends Expr> args, List<VarCodeGen> vcgs) {
        if (vcgs == null) {
            for (Expr arg : args) {
                arg.accept(this);
            }
        } else {
            int n = args.size();
            if (args.size() != vcgs.size()) {
                sayWhat(args.get(0), "Internal error: number of args does not match number of consumers.");
            }
            for (int i = 0; i < n; i++) {
                VarCodeGen vcg = vcgs.get(i);
                vcg.prepareAssignValue(mv);
                args.get(i).accept(this);
                vcg.assignValue(mv);
            }
        }
    }

    public void forOpExpr(OpExpr x) {
        debug("forOpExpr ", x, " op = ", x.getOp(),
                     " of class ", x.getOp().getClass(),  " args = ", x.getArgs());
        FunctionalRef op = x.getOp();
        List<Expr> args = x.getArgs();

        if (pa.worthParallelizing(x)) {
            forExprsParallel(args, null);
        } else {
            for (Expr arg : args) {
                arg.accept(this);
            }
        }

        op.accept(this);

    }

    public void forOpRef(OpRef x) {
        forFunctionalRef(x);
   }

    public void forStringLiteralExpr(StringLiteralExpr x) {
        // This is cheating, but the best we can do for now.
        // We make a FString and push it on the stack.
        debug("forStringLiteral ", x);
        addLineNumberInfo(x);
        mv.visitLdcInsn(x.getText());
        addLineNumberInfo(x);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, NamingCzar.internalFortressString, NamingCzar.make,
                           NamingCzar.makeMethodDesc(NamingCzar.descString, NamingCzar.descFortressString));
    }

    public void forSubscriptExpr(SubscriptExpr x) {
        // TODO: FIX!!  Only works for string subscripting.  Why does this
        // AST node still exist at all at this point in compilation??
        // It ought to be turned into a MethodInvocation.
        // JWM 9/4/09
        debug("forSubscriptExpr ", x);
        Expr obj = x.getObj();
        List<Expr> subs = x.getSubs();
        Option<Op> maybe_op = x.getOp();
        List<StaticArg> staticArgs = x.getStaticArgs();
        boolean canCompile = staticArgs.isEmpty() && maybe_op.isSome() && (obj instanceof VarRef);
        if (!canCompile) { sayWhat(x); return; }
        Op op = maybe_op.unwrap();
        VarRef var = (VarRef) obj;
        Id id = var.getVarId();

        debug("ForSubscriptExpr  ", x, "obj = ", obj,
              " subs = ", subs, " op = ", op, " static args = ", staticArgs,
              " varRef = ", NamingCzar.idToString(id));

        var.accept(this);

        for (Expr e : subs) {
            debug("calling accept on ", e);
            e.accept(this);
        }
        addLineNumberInfo(x);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                           NamingCzar.makeInnerClassName(id),
                           // Naming.mangleIdentifier(opToString(op)),
                           NamingCzar.opToString(op),
                           "(Lcom/sun/fortress/compiler/runtimeValues/FZZ32;)Lcom/sun/fortress/compiler/runtimeValues/FString;");
    }

    public void forTraitDecl(TraitDecl x) {
        debug("forTraitDecl", x);
        TraitTypeHeader header = x.getHeader();
        TraitTypeHeader original_header = x.getHeader();
        List<TraitTypeWhere> extendsC = header.getExtendsClause();
        boolean canCompile =
            // NOTE: Presence of excludes or comprises clauses should not
            // affect code generation once type checking is complete.
            header.getWhereClause().isNone() &&   // no where clause
            header.getThrowsClause().isNone() &&  // no throws clause
            header.getContract().isNone() &&      // no contract
            Modifiers.TraitMod.containsAll(header.getMods());
        debug("forTraitDecl", x,
                    " decls = ", header.getDecls(), " extends = ", extendsC);
        if ( !canCompile ) sayWhat(x);

        Map<String, String> xlation = new HashMap<String, String>();
        List<String> splist = new ArrayList<String>();
        List<StaticParam> original_static_params = header.getStaticParams();
        String sparams_part = NamingCzar.genericDecoration(original_static_params, xlation, splist, thisApi());

        TraitDecl y = x;


//       First let's do the interface class
//        String classFile = NamingCzar.makeInnerClassName(packageAndClassName,
//                                                         NodeUtil.getName(x).getText());

        /*
         * This will want refactoring into NamingCzar sooner or later.
         * I decided that the least-confusion convention for implementation
         * classes for generic traits was to use the Generic[\parameters\]$whatever
         * convention.  This may require enhancements to the mangling code, but
         * once that is done it will cause least-confusion for everyone else
         * later.
         */
        Id classId = NodeUtil.getName(x);
        String classFile =
            NamingCzar.jvmClassForToplevelTypeDecl(classId,
                    sparams_part,
                    packageAndClassName);

        // Used just for the name of the class file, nothing else.
        String classFileOuter =
            NamingCzar.jvmClassForToplevelTypeDecl(classId,
                    makeTemplateSParams(sparams_part) ,
                    packageAndClassName);

        String erasedSuperI = sparams_part.length() > 0 ? NamingCzar
                .jvmClassForToplevelTypeDecl(classId, "", packageAndClassName)
                : "";
                if (sparams_part.length() > 0) {
                    emitErasedClassFor(erasedSuperI, (TraitObjectDecl) x);
                }


        inATrait = true;
        currentTraitObjectDecl = x;

        springBoardClass = classFile + NamingCzar.springBoard;
        String springBoardClassOuter = classFileOuter + NamingCzar.springBoard;

        String abstractSuperclass;
        traitOrObjectName = classFile;
        String[] superInterfaces = NamingCzar.extendsClauseToInterfaces(
                extendsC, component.getName(), erasedSuperI);
        if (classFile.equals("fortress/AnyType$Any")) {
            superInterfaces = new String[0];
            abstractSuperclass = NamingCzar.FValueType;
        } else {
            abstractSuperclass = superInterfaces[0] + NamingCzar.springBoard;
        }
        CodeGenClassWriter prev = cw;
        cw = new CodeGenClassWriter(ClassWriter.COMPUTE_FRAMES, prev);
        cw.visitSource(NodeUtil.getSpan(x).begin.getFileName(), null);
        cw.visit( Opcodes.V1_5,
                  Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                  classFile, null, NamingCzar.internalObject, superInterfaces);
        dumpSigs(header.getDecls());
        if (sparams_part.length() > 0 ) {
            cw.dumpClass( classFileOuter, splist );
        } else {
            cw.dumpClass( classFileOuter );
        }

        // Now let's do the springboard inner class that implements this interface.
        cw = new CodeGenClassWriter(ClassWriter.COMPUTE_FRAMES, prev);
        cw.visitSource(NodeUtil.getSpan(x).begin.getFileName(), null);
        // Springboard *must* be abstract if any methods / fields are abstract!
        // In general Springboard must not be directly instantiable.
        cw.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, springBoardClass,
                 null, abstractSuperclass, new String[] { classFile } );
        debug("Start writing springboard class ",
              springBoardClass);
        generateFieldsAndInitMethod(springBoardClass, abstractSuperclass,
                                    Collections.<Param>emptyList());
        debug("Finished init method ", springBoardClass);
        dumpTraitDecls(header.getDecls());
        dumpMethodChaining(superInterfaces, true);
        dumpErasedMethodChaining(superInterfaces, true);
        debug("Finished dumpDecls ", springBoardClass);
        if (sparams_part.length() > 0 ) {
            cw.dumpClass( springBoardClassOuter, splist );
        } else {
            cw.dumpClass( springBoardClassOuter );
        }
        // Now lets dump out the functional methods at top level.
        cw = prev;

        emittingFunctionalMethodWrappers = true;
        // Have to use the origial header to get the signatures right.
        dumpTraitDecls(original_header.getDecls());
        emittingFunctionalMethodWrappers = false;

        debug("Finished dumpDecls for parent");
        inATrait = false;
        currentTraitObjectDecl = null;
        traitOrObjectName = null;
        springBoardClass = null;
    }

    public void forVarDecl(VarDecl v) {
        // Assumption: we already dealt with this VarDecl in pre-pass.
        // Therefore we can just skip it.
        debug("forVarDecl ",v," should have been seen during pre-pass.");
    }

    /** Supposed to be called with nested codegen context. */
    private void generateVarDeclInnerClass(VarDecl x, String classFile, String tyDesc, Expr exp) {
        cw = new CodeGenClassWriter(ClassWriter.COMPUTE_FRAMES, cw);
        cw.visitSource(NodeUtil.getSpan(x).begin.getFileName(), null);
        cw.visit( Opcodes.V1_5, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER + Opcodes.ACC_FINAL,
                  classFile, null, NamingCzar.internalSingleton, null );
        cw.visitField(Opcodes.ACC_PUBLIC + Opcodes.ACC_STATIC + Opcodes.ACC_FINAL,
                      NamingCzar.SINGLETON_FIELD_NAME, tyDesc, null, null);
        mv = cw.visitCGMethod(Opcodes.ACC_STATIC,
                            "<clinit>", NamingCzar.voidToVoid, null, null);
        exp.accept(this);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, classFile,
                          NamingCzar.SINGLETON_FIELD_NAME, tyDesc);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(NamingCzar.ignore, NamingCzar.ignore);
        mv.visitEnd();
        cw.dumpClass( classFile );
    }

    private void forVarDeclPrePass(VarDecl v) {
        List<LValue> lhs = v.getLhs();
        Option<Expr> oinit = v.getInit();
        if (lhs.size() != 1) {
            sayWhat(v,"VarDecl "+v+" tupled lhs not handled.");
        }
        if (!oinit.isSome()) {
            debug("VarDecl ", v, " skipping abs var decl.");
            return;
        }
        LValue lv = lhs.get(0);
        if (lv.isMutable()) {
            sayWhat(v,"VarDecl "+v+" mutable bindings not yet handled.");
        }
        Id var = lv.getName();
        Type ty = (Type)lv.getIdType().unwrap();
        Expr exp = oinit.unwrap();
        String classFile = NamingCzar.jvmClassForToplevelDecl(var, packageAndClassName);
        String tyDesc = NamingCzar.jvmTypeDesc(ty, thisApi());
        debug("VarDeclPrePass ", var, " : ", ty, " = ", exp);
        new CodeGen(this).generateVarDeclInnerClass(v, classFile, tyDesc, exp);

        addStaticVar(
            new VarCodeGen.StaticBinding(var, ty, classFile,
                                         NamingCzar.SINGLETON_FIELD_NAME, tyDesc));
    }

    public void forVarRef(VarRef v) {
        List<StaticArg> lsargs = v.getStaticArgs();
        Id id = v.getVarId();
        VarCodeGen vcg = getLocalVarOrNull(id);
        if (vcg == null) {
            debug("forVarRef fresh import ", v);
            Type ty = NodeUtil.getExprType(v).unwrap();
            String tyDesc = NamingCzar.jvmTypeDesc(ty, thisApi());
            String className = NamingCzar.jvmClassForToplevelDecl(id, packageAndClassName);
            vcg = new VarCodeGen.StaticBinding(id, ty,
                                               className,
                                               NamingCzar.SINGLETON_FIELD_NAME, tyDesc);
            addStaticVar(vcg);
        }
        debug("forVarRef ", v , " Value = ", vcg);
        addLineNumberInfo(v);

        String static_args = NamingCzar.genericDecoration(lsargs, thisApi());
        vcg.pushValue(mv, static_args);
    }

    private void pushVoid() {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, NamingCzar.internalFortressVoid, NamingCzar.make,
                           NamingCzar.makeMethodDesc("", NamingCzar.descFortressVoid));
    }

    public void forVoidLiteralExpr(VoidLiteralExpr x) {
        debug("forVoidLiteral ", x);
        addLineNumberInfo(x);
        pushVoid();
    }
    
    
    public void forMethodInvocation(MethodInvocation x) {
        debug("forMethodInvocation ", x,
              " obj = ", x.getObj(),
              " method = ", x.getMethod(),
              " static args = ", x.getStaticArgs(),
              " args = ", x.getArg());
        Id method = x.getMethod();
        Expr obj = x.getObj();
        List<StaticArg> method_sargs = x.getStaticArgs();
        Expr arg = x.getArg();

        Option<Type> mt = x.getOverloadingType();
        Type domain_type;
        Type range_type;
        if ((mt.isSome())) {
            ArrowType sigtype = (ArrowType) mt.unwrap();
            domain_type = sigtype.getDomain();
            range_type = sigtype.getRange();
        } else {
            // TODO: some method applications (particularly those
            // introduced during getter desugaring) don't have an
            // OverloadingType.  Fix?  Or live with it?
            domain_type = exprType(arg);
            range_type = exprType(x);
        }

        Type receiverType = exprType(obj);
        if (!(receiverType instanceof TraitType)) {
            sayWhat(x, "receiver type "+receiverType+" is not TraitType in " + x);
        }

        int savedParamCount = paramCount;
        try {
            
            if (method_sargs.size() > 0) {
                /* Have to emit some interesting code.
                 * 
                 * Want to call object.methodnameSCHEMA
                 * to obtain an object, which we then cast to
                 * an appropriate arrow type (with an explicit leading SELF,
                 * using our static type) which we then invoke.
                 * 
                 * Must pass in a long constant that is the hashcode
                 * (but it could just be a random number, our "hashcode" is the
                 * one that is used everywhere)
                 * 
                 * Must pass in a string correctly describing our static args.
                 * 
                 * The convention for the string must agree with
                 * InstantiatingClassloader.findGenericMethodClosure
                 */
                
                // DRC-WIP
                
                String string_sargs = NamingCzar.genericDecoration(method_sargs, thisApi());
              
                long hash_sargs = MagicNumbers.hashStringLong(string_sargs);
                
                // assumption -- Schema had better be here.
                Type overloading_schema = x.getOverloadingSchema().unwrap();
                
                String methodName = genericMethodName(method, (ArrowType) overloading_schema);
                
                obj.accept(this);
                // Need to dup this so we will not re-eval.
                mv.visitInsn(Opcodes.DUP);
                
                // compute hashcode statically, push constant,
                mv.visitLdcInsn(new Long(hash_sargs));
                
                // compute String, push constant
                mv.visitLdcInsn(string_sargs);
                
                // invoke the oddly named method
                methodCall(methodName, (TraitType)receiverType, genericMethodClosureFinderSig);
                
                // cast the result
                
                 
                // swap w/ TOS
                mv.visitInsn(Opcodes.SWAP);

                // evaluate args
                evalArg(arg);

                // method call (calling a closure, really) 
                
            } else {
                // put object on stack
                obj.accept(this);
                // put args on stack
                evalArg(arg);
                methodCall(method, (TraitType)receiverType, domain_type, range_type);
            }
        } finally {
            paramCount = savedParamCount;
        }

    }

    /**
     * @param expr
     * @return
     */
    private Type exprType(Expr expr) {
        Option<Type> exprType = expr.getInfo().getExprType();

        if (!exprType.isSome()) {
            sayWhat(expr, "Missing type information for " + expr);
        }

        Type ty = exprType.unwrap();
        if (ty instanceof TraitSelfType)
            ty = ((TraitSelfType)ty).getNamed();

        return ty;
    }
    private Option<Type> exprOptType(Expr expr) {
        Option<Type> exprType = expr.getInfo().getExprType();

        return exprType;
    }

    /**
     * @param arg
     */
    private void evalArg(Expr arg) {
        if (arg instanceof VoidLiteralExpr) {
            paramCount = 0;
        } else if (arg instanceof TupleExpr) {
            TupleExpr targ = (TupleExpr) arg;
            List<Expr> exprs = targ.getExprs();
            for (Expr expr : exprs) {
                expr.accept(this);
            }
            paramCount = exprs.size();
        } else {
            paramCount = 1; // for now; need to dissect tuple and do more.
            arg.accept(this);
        }
    }

    private void generateHigherOrderCall(Type t) {
        if (!(t instanceof ArrowType)) {
            sayWhat(t,"Higher-order call to non-arrow type " + t);
        }
        ArrowType at = (ArrowType)t;
        String desc = NamingCzar.makeArrowDescriptor(at, thisApi());
        String sig = NamingCzar.jvmSignatureFor(at,thisApi());
        // System.err.println(desc+".apply"+sig+" call");
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, desc,
                           Naming.APPLY_METHOD, sig);
    }

    public void for_RewriteFnApp(_RewriteFnApp x) {
        debug("for_RewriteFnApp ", x,
                     " args = ", x.getArgument(), " function = ", x.getFunction(),
              " function class = ", x.getFunction());
        // This is a little weird.  If a function takes no arguments the parser gives me a void literal expr
        // however I don't want to be putting a void literal on the stack because it gets in the way.
        int savedParamCount = paramCount;
        boolean savedFnRefIsApply = fnRefIsApply;
        try {
            Expr arg = x.getArgument();
            Expr fn = x.getFunction();
            if (!(fn instanceof FunctionalRef)) {
                // Higher-order call.
                fn.accept(this); // Puts the VarRef function on the stack.
            }
            fnRefIsApply = false;
            evalArg(arg);
            fnRefIsApply = true;
            if (!(fn instanceof FunctionalRef)) {
                generateHigherOrderCall(exprType(fn));
            } else {
                x.getFunction().accept(this);
            }
        } finally {
            paramCount = savedParamCount;
            fnRefIsApply = savedFnRefIsApply;
        }
    }

    public void for_RewriteFnOverloadDecl(_RewriteFnOverloadDecl x) {
        /* Note for refactoring -- this code does it the "right" way.
         * And also, this code NEEDS refactoring.
         */
        List<IdOrOp> fns = x.getFns();
        IdOrOp name = x.getName();
        com.sun.fortress.nodes.Type ty = x.getType().unwrap();
        Relation<IdOrOpOrAnonymousName, Function> fnrl = ci.functions();

        MultiMap<Integer, OverloadSet.TaggedFunctionName> byCount =
            new MultiMap<Integer,OverloadSet.TaggedFunctionName>();

        // System.err.println(NodeUtil.getSpan(x) + ": _RewriteFnOverloadDecl " + name +
        //                    "\n  candidates " + fns);

        for (IdOrOp fn : fns) {

            Option<APIName> fnapi = fn.getApiName();
            PredicateSet<Function> set_of_f;
            APIName apiname;

            if (fnapi.isNone()) {
                apiname = thisApi();
                set_of_f = fnrl.matchFirst(fn);
            } else {

                IdOrOp fnnoapi = NodeFactory.makeLocalIdOrOp(fn);
                apiname = fnapi.unwrap();
                ApiIndex ai = env.api(apiname);
                set_of_f = ai.functions().matchFirst(fnnoapi);
            }

            for (Function f : set_of_f) {
                /* This guard should be unnecessary when proper overload
                   disambiguation is working.  Right now, the types are
                   "too accurate" which causes a call to an otherwise
                   non-existent static method.
                */
                if (true || OverloadSet.functionInstanceofType(f, ty, ta)) {
                    OverloadSet.TaggedFunctionName tagged_f =
                        new OverloadSet.TaggedFunctionName(apiname, f);
                    byCount.putItem(f.parameters().size(), tagged_f);
                }
            }
        }

        for (Map.Entry<Integer, Set<OverloadSet.TaggedFunctionName>> entry :
                 byCount.entrySet()) {
            int i = entry.getKey();
            Set<OverloadSet.TaggedFunctionName> fs = entry.getValue();
            if (fs.size() > 1) {
                OverloadSet os = new OverloadSet.AmongApis(thisApi(), name,
                                                           ta, fs, i);

                os.split(false);
                os.generateAnOverloadDefinition(name.stringName(), cw);

            }
        }

    }


    /**
     * Creates overloaded functions for any overloads present at the top level
     * of this component.  Top level overloads are those that might be exported;
     * Reference overloads are rewritten into _RewriteFnOverloadDecl nodes
     * and generated in the normal visits.
     */
    public static Set<String> generateTopLevelOverloads(APIName api_name,
            Map<IdOrOpOrAnonymousName,MultiMap<Integer, Function>> size_partitioned_overloads,
            TypeAnalyzer ta,
            CodeGenClassWriter cw, CodeGen cg
            ) {

        Set<String> overloaded_names_and_sigs = new HashSet<String>();

        for (Map.Entry<IdOrOpOrAnonymousName, MultiMap<Integer, Function>> entry1 :
                 size_partitioned_overloads.entrySet()) {
            IdOrOpOrAnonymousName  name = entry1.getKey();
            MultiMap<Integer, Function> partitionedByArgCount = entry1.getValue();

            for (Map.Entry<Integer, Set<Function>> entry :
                     partitionedByArgCount.entrySet()) {
               int i = entry.getKey();
               Set<Function> fs = entry.getValue();

               OverloadSet os =
                   new OverloadSet.Local(api_name, name,
                                         ta, fs, i);

               os.split(true);

               String s = name.stringName();
               String s2 = NamingCzar.apiAndMethodToMethod(api_name, s);

               os.generateAnOverloadDefinition(s2, cw);
               if (cg != null) {
                   /* Need to check if the overloaded function happens to match
                    * a name in an API that this component exports; if so,
                    * generate a forwarding wrapper from the
                    */
               }

               for (Map.Entry<String, OverloadSet> o_entry : os.getOverloadSubsets().entrySet()) {
                   String ss = o_entry.getKey();
                   OverloadSet o_s = o_entry.getValue();
                   ss = // s +
                   ss + o_s.genericSchema;
                   // Need to add Schema to the end of ss for generic overloads.
                   // System.err.println("Adding "+s+" : "+ss);
                   overloaded_names_and_sigs.add(ss);
               }
           }
        }
        // StringBuilder sb = new StringBuilder("api ");
        // sb.append(api_name);
        // sb.append(" has overloads:\n");
        // for (String s : overloaded_names_and_sigs) {
        //     sb.append("  ");
        //     sb.append(s);
        //     sb.append("\n");
        // }
        // System.err.println(sb.toString());
        return overloaded_names_and_sigs;
    }

    public static Map<IdOrOpOrAnonymousName, MultiMap<Integer, Function>>
       sizePartitionedOverloads(Relation<IdOrOpOrAnonymousName, Function> fns) {

        Map<IdOrOpOrAnonymousName, MultiMap<Integer, Function>> result =
            new HashMap<IdOrOpOrAnonymousName, MultiMap<Integer, Function>>();

        for (IdOrOpOrAnonymousName name : fns.firstSet()) {
            Set<Function> defs = fns.matchFirst(name);
            if (defs.size() <= 1) continue;

            MultiMap<Integer, Function> partitionedByArgCount =
                new MultiMap<Integer, Function>();

            for (Function d : defs) {
                partitionedByArgCount.putItem(d.parameters().size(), d);
            }

            for (Function d : defs) {
                Set<Function> sf = partitionedByArgCount.get(d.parameters().size());
                if (sf != null && sf.size() <= 1)
                    partitionedByArgCount.remove(d.parameters().size());
            }
            if (partitionedByArgCount.size() > 0)
                result.put(name, partitionedByArgCount);
        }

        return result;
    }

    private List<Decl> topSortDeclsByDependencies(List<Decl> decls) {
        HashMap<IdOrOp, TopSortItemImpl<Decl>> varToNode =
            new HashMap<IdOrOp, TopSortItemImpl<Decl>>(2 * decls.size());
        List<TopSortItemImpl<Decl>> nodes = new ArrayList(decls.size());
        for (Decl d : decls) {
            TopSortItemImpl<Decl> node =
                new TopSortItemImpl<Decl>(d);
            nodes.add(node);
            if (d instanceof VarDecl) {
                VarDecl vd = (VarDecl)d;
                for (LValue lv : vd.getLhs()) {
                    varToNode.put(lv.getName(), node);
                }
            } else if (d instanceof TraitObjectDecl) {
                TraitObjectDecl tod = (TraitObjectDecl)d;
                IdOrOpOrAnonymousName name = tod.getHeader().getName();
                if (name instanceof IdOrOp) {
                    varToNode.put((IdOrOp)name, node);
                }
            } else {
                sayWhat(d, " can't sort non-value-creating decl by dependencies.");
            }
        }
        for (TopSortItemImpl<Decl> node : nodes) {
            for (IdOrOp freeVar : fv.freeVars(node.x)) {
                TopSortItemImpl<Decl> dest = varToNode.get(freeVar);
                if (dest != null && dest != node) {
                    dest.edgeTo(node);
                }
            }
        }
        // TODO: can't handle cycles!
        nodes = TopSort.depthFirst(nodes);
        List<Decl> result = new ArrayList(nodes.size());
        for (TopSortItemImpl<Decl> node : nodes) {
            result.add(node.x);
        }
        return result;
    }

    /**
     * Traits compile to interfaces.  These are all the abstract methods that
     * the interface will require.
     *
     * @param decls
     */
    private void dumpSigs(List<Decl> decls) {
        debug("dumpSigs", decls);
        for (Decl d : decls) {
            debug("dumpSigs decl =", d);
            if (!(d instanceof FnDecl)) {
                sayWhat(d);
                return;
            }

            FnDecl f = (FnDecl) d;
            FnHeader h = f.getHeader();

            List<Param> params = h.getParams();
            int selfIndex = selfParameterIndex(params);
            boolean  functionalMethod = selfIndex != NO_SELF;

            IdOrOpOrAnonymousName xname = h.getName();
            IdOrOp name = (IdOrOp) xname;

            String desc = NamingCzar.jvmSignatureFor(f,component.getName());
            if (functionalMethod) {
                desc = Naming.removeNthSigParameter(desc, selfIndex);
            }

            // TODO what about overloading collisions in an interface?
            // it seems wrong to publicly mangle.
            String mname = functionalMethod ? fmDottedName(
                            singleName(name), selfIndex) : nonCollidingSingleName(
                                    name, desc, ""); // static params?

            mv = cw.visitCGMethod(Opcodes.ACC_ABSTRACT + Opcodes.ACC_PUBLIC,
                                mname, desc, null, null);

            mv.visitMaxs(NamingCzar.ignore, NamingCzar.ignore);
            mv.visitEnd();
        }
    }

}
