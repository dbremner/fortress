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

package com.sun.fortress.syntax_abstractions.rats;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import xtc.parser.Module;
import xtc.parser.ModuleDependency;
import xtc.parser.ModuleInstantiation;
import xtc.parser.ModuleList;
import xtc.parser.ModuleName;
import xtc.parser.PParser;
import xtc.parser.ParseError;
import xtc.parser.ParserBase;
import xtc.parser.PrettyPrinter;
import xtc.parser.Result;
import xtc.parser.SemanticValue;
import xtc.tree.Comment;
import xtc.tree.Printer;
import xtc.type.JavaAST;

import com.sun.fortress.repository.ProjectProperties;
import com.sun.fortress.useful.Useful;
import com.sun.fortress.useful.Debug;
import com.sun.fortress.Shell;

import edu.rice.cs.plt.tuple.Option;
import edu.rice.cs.plt.io.IOUtil;

public abstract class RatsUtil {

    private static final char SEP = File.separatorChar; // '/';
    public static final String BASEPARSER =
        "com"+ SEP +"sun"+SEP+"fortress"+SEP+"parser"+SEP;
    public static final String TEMPLATEPARSER =
        BASEPARSER + "templateparser" + SEP;

    public static Module getRatsModule(String filename) {
        Option<Module> result = parseRatsModule(filename);
        if (result.isNone()) {
            System.err.println("FAIL: Errors parsing the Rats! file " + filename);
            System.exit(1);
            return null;
        } else
            return result.unwrap();
    }

    private static Option<Module> parseRatsModule(String filename) {
        BufferedReader in;
        try {
            in = Useful.utf8BufferedFileReader(filename);
            xtc.parser.PParser parser = new PParser(in,
                    filename,
                    (int) new File(filename).length());
            Result r = parser.pModule(0);
            if (r.hasValue()) {
                SemanticValue v = (SemanticValue) r;
                Module n = (Module) v.value;
                return Option.some(n);
            }
            ParseError err = (ParseError) r;
            if (-1 != err.index) {
                System.err.println("  " + parser.location(err.index) + ": "
                        + err.msg);
            }
        } catch (FileNotFoundException e) {
            if (Debug.isOnMax()) {
                System.err.println(e.getMessage());
                e.printStackTrace();
            } else {
                System.err.println(Shell.turnOnDebugMessage);
            }
        } catch (IOException e) {
            if (Debug.isOnMax()) {
                System.err.println(e.getMessage());
                e.printStackTrace();
            } else {
                System.err.println(Shell.turnOnDebugMessage);
            }
        }
        return Option.none();
    }

    public static void writeRatsModule(Module module, String tempDir) {
        FileOutputStream fo;
        try {
            makeSureDirectoryExists(tempDir);
            String name = getModulePath(module.name.name);
            File file = new File(tempDir+name+".rats");
            fo = new FileOutputStream(file);
            PrettyPrinter pp = new PrettyPrinter(new Printer(fo), new JavaAST(),
                                                 true);
            pp.visit(module);
            pp.flush();
            fo.flush();
            fo.close();
        } catch (FileNotFoundException e) {
            if (Debug.isOnMax()) {
                System.err.println(e.getMessage());
                e.printStackTrace();
            } else {
                System.err.println(Shell.turnOnDebugMessage);
            }
        } catch (IOException e) {
            if (Debug.isOnMax()) {
                System.err.println(e.getMessage());
                e.printStackTrace();
            } else {
                System.err.println(Shell.turnOnDebugMessage);
            }
        }
    }

    private static void makeSureDirectoryExists(String tempDir) {
        File dir = new File(tempDir+TEMPLATEPARSER);
        if (!dir.isDirectory()) {
            if (!dir.mkdirs()) {
                throw new RuntimeException("Could not create directories: "+
                                           dir.getAbsolutePath());
            }
        }
    }

    private static String getModulePath(String dottedName) {
        return dottedName.replace('.', SEP);
    }

    public static String getParserPath() {
        return getFortressSrcDir() + BASEPARSER;
    }

    public static String getTemplateParserPath() {
        return getFortressSrcDir() + TEMPLATEPARSER;
    }

    public static String getTempDir() {
        try {
            return IOUtil.createAndMarkTempDirectory("fortress","rats").getCanonicalPath() + SEP;
        } catch ( IOException e ){
            return System.getProperty("java.io.tmpdir")+SEP;
        }
    }

    private static String getFortressSrcDir() {
        return ProjectProperties.FORTRESS_AUTOHOME+SEP+"ProjectFortress"+SEP+"src"+SEP;
    }

    /* Fresh name stuff */
    private static int freshid = 0;

    public static String getFreshName(String s) {
        return s+(++freshid);
    }

    public static void resetFreshName() {
        freshid = 0;
    }

    /* From ParserMediator.java */

    /**
     * Instantiate a new instance of the given parserClass
     * which must be a subtype of xtc.parser.ParserBase.
     * The instantiated parser object is stored in a field and returned;
     * if anything goes wrong, an exception is thrown.
     * @param parserClass
     * @param reader
     * @param filename
     * @return The new instantiated parser object
     * @throws IllegalArgumentException
     * @throws InstantiationException
     * @throws IllegalAccessException
     * @throws InvocationTargetException
     * @throws SecurityException
     * @throws NoSuchMethodException
     */
    public static ParserBase getParser(Class<?> parserClass,
                                       BufferedReader reader,
                                       String filename)
        throws IllegalArgumentException, InstantiationException,
               IllegalAccessException, InvocationTargetException,
               SecurityException, NoSuchMethodException {
        Constructor<?> constructor =
            parserClass.getConstructor(Reader.class, String.class);
        return (ParserBase) constructor.newInstance(reader, filename);
    }

    /**
     * Call the method pFile(0) on the current parser object
     * created using instantiation.
     * @return The xtc.parser.Result object returned by the call to pFile(0).
     * @throws IllegalArgumentException
     * @throws SecurityException
     * @throws IllegalAccessException
     * @throws InvocationTargetException
     * @throws NoSuchMethodException
     */
    public static Result parse( ParserBase parser )
        throws IllegalArgumentException, SecurityException,
               IllegalAccessException, InvocationTargetException,
               NoSuchMethodException {
        String methodName = "pFile";
        Class[] types = new Class[] {int.class};
        Object args = 0;
        return (Result) invokeMethod(parser, methodName, types, args);
    }

    /**
     * Look up the given method in the current parser object
     * using reflection using the supplied argument types.
     * The method is invoked with the supplied arguments.
     * If no method exists with the given name and argument types,
     * an exception is thrown.
     * If the arguments are not consistent with the declared types,
     * an exception is thrown.
     * @param methodName
     * @param types
     * @param args
     * @throws InvocationTargetException
     * @throws IllegalAccessException
     * @throws IllegalArgumentException
     * @throws NoSuchMethodException
     * @throws SecurityException
     */
    private static Object invokeMethod(ParserBase parser, String methodName,
                                       Class[] types, Object args)
        throws IllegalArgumentException, IllegalAccessException,
               InvocationTargetException, SecurityException,
               NoSuchMethodException {
        Method method = parser.getClass().getMethod(methodName, types);
        Object o = method.invoke(parser, args);
        return o;
    }

}
