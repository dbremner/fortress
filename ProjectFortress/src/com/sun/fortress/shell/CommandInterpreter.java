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

package com.sun.fortress.shell;

import java.io.*;
import java.util.regex.Pattern;
import java.util.*;
import edu.rice.cs.plt.tuple.Option;

import com.sun.fortress.compiler.*;
import com.sun.fortress.compiler.index.*;
import com.sun.fortress.exceptions.FortressException;
import com.sun.fortress.exceptions.StaticError;
import com.sun.fortress.exceptions.WrappedException;
import com.sun.fortress.exceptions.shell.RepositoryError;
import com.sun.fortress.exceptions.shell.ShellException;
import com.sun.fortress.exceptions.shell.UserError;
import com.sun.fortress.interpreter.drivers.*;
import com.sun.fortress.nodes.CompilationUnit;
import com.sun.fortress.nodes.Component;
import com.sun.fortress.nodes.Api;
import com.sun.fortress.useful.Path;
import com.sun.fortress.useful.Debug;

import static com.sun.fortress.shell.ConvenientStrings.*;
import static com.sun.fortress.exceptions.InterpreterBug.bug;

import java.io.*;

public class CommandInterpreter {
    private Shell shell;

    // public static boolean debug;
    boolean test;
    boolean nolib;

    CommandInterpreter(Shell _shell) {
        shell = _shell;
    }

    /**
     * This compiler uses a different method for determining what to compile,
     * and how to compile it.
     *
     * @param doLink
     * @param s
     * @throws UserError
     * @throws InterruptedException
     * @throws IOException
     */
    void compile(boolean doLink, String s) throws UserError, InterruptedException, IOException {
        try {
            //FortressRepository fileBasedRepository = new FileBasedRepository(shell.getPwd());
            Fortress fortress = new Fortress(new CacheBasedRepository(ProjectProperties.ANALYZED_CACHE_DIR));

            Path path = ProjectProperties.SOURCE_PATH;

            if (s.contains("/")) {
                String head = s.substring(0, s.lastIndexOf("/"));
                s = s.substring(s.lastIndexOf("/")+1, s.length());
                path = path.prepend(head);
            }

            Iterable<? extends StaticError> errors = fortress.compile(path, s);

            for (StaticError error: errors) {
                System.err.println(error);
            }
            // If there are no errors, all components will have been written to disk by the FileBasedRepository.
        }
        catch (RepositoryError error) {
            System.err.println(error);
        }
    }

    /* Upgrade the internal files of the resident fortress with the contents of the given tar file.*/
    void selfUpgrade(String fileName) throws UserError, InterruptedException {
        //Ant.run("selfupgrade", "-Dtarfile=" + fileName);
    }

    /* Convenience method for calling selfUpgrade directly with a file.*/
    void selfUpgrade(File file) throws UserError, InterruptedException { selfUpgrade(file.getPath()); }

    /* Checks whether a component or API has been installed in the resident fortress.*/
    void exists(String componentName) throws UserError {
        if (isInstalled(componentName)) {
            System.out.println("Yes, component " + componentName + " is installed in this fortress.");
        }
        else if (new File(shell.getComponents() + SEP + "apis" + SEP + componentName + SEP + ".jst").exists()) {
            System.out.println("Yes, API " + componentName + " is installed in this fortress.");
        }
        else {
            System.out.println("No, there is no component or API with name " + componentName + " installed in this fortress.");
        }
    }

    /* Runs a fortress source file directly.*/
    void script(String fileName) throws UserError, IOException {
        Option<CompilationUnit> cu = makeCompilationUnit(fileName);
        if (cu.isSome())
            Driver.evalComponent(cu.unwrap(),
                    new PathBasedRepository(ProjectProperties.SOURCE_PATH));
        else bug("Failed to make a compilation unit for " + fileName);
    }

    private boolean isInteger( String s ){
        try{
            int i = Integer.valueOf(s);
            return i == i;
        } catch ( NumberFormatException n ){
            return false;
        }
    }

    void run(List<String> args) throws UserError, IOException, Throwable {
        if (args.size() == 0) {
            throw new UserError("Need a file to run");
        }
        String s = args.get(0);
        List<String> rest = args.subList(1, args.size());

        if (s.startsWith("-")) {
            if (s.equals("-debug")){
                Debug.setDebug( 99 );
                if ( ! rest.isEmpty() && isInteger( rest.get( 0 ) ) ){
                    Debug.setDebug( Integer.valueOf( rest.get( 0 ) ) );
                    rest = rest.subList( 1, rest.size() );
                } else {
                    ProjectProperties.debug = true;
                }
            }
            if (s.equals("-test")) test= true;
            if (s.equals("-nolib")) nolib= true;
            if (s.equals("-noPreparse")) ProjectProperties.noPreparse = true;
            run(rest);
        } else {
            run(s, rest);
        }
    }
    void run(String fileName, List<String> args) throws UserError, IOException, Throwable {
        try {
            //FortressRepository fileBasedRepository = new FileBasedRepository(shell.getPwd());
            Fortress fortress = new Fortress(new CacheBasedRepository(ProjectProperties.ANALYZED_CACHE_DIR));

            Path path = ProjectProperties.SOURCE_PATH;

            if (fileName.contains("/")) {
                String head = fileName.substring(0, fileName.lastIndexOf("/"));
                fileName = fileName.substring(fileName.lastIndexOf("/")+1, fileName.length());
                path = path.prepend(head);
            }

            /*
             * Strip suffix to get a bare component name.
             */
            if (fileName.endsWith("." + ProjectProperties.COMP_SOURCE_SUFFIX)) {
                fileName = fileName.substring(0, fileName.length() - ProjectProperties.COMP_SOURCE_SUFFIX.length());
            }

            Iterable<? extends StaticError> errors = fortress.run(path, fileName, test, nolib, args);

            /* FIXME: this is a hack to get around some null pointer exceptions
             * if a WrappedException is printed as-is.
             */
            for (StaticError error: errors) {
                System.err.println(error);
            }
            // If there are no errors, all components will have been written to disk by the FileBasedRepository.
        }
        catch (RepositoryError e) {
            System.err.println(e.getMessage());
        }
        catch (FortressException e) {
            System.err.println(e.getMessage());
            e.printInterpreterStackTrace(System.err);
            if (ProjectProperties.debug) {
                e.printStackTrace();
            } else {
                System.err.println("Turn on -debug for Java-level error dump.");
            }
            System.exit(1);
        }
    }

    void link(String result, String left, String right) throws UserError { throw new UserError("Error: Link not yet implemented!"); }
    void upgrade(String result, String left, String right) throws UserError { throw new UserError("Error: Upgrade not yet implemented!"); }

    void api(String fileName) throws IOException, UserError {
        FileBasedRepository fileBasedRepository = new FileBasedRepository(shell.getPwd());
        Fortress fortress = new Fortress(fileBasedRepository);

        File file = new File(fileName);
        Iterable<Component> _components = Parser.parse(file).components();

        // Compile to ensure there are no static errors.
        // Disable until static checking is actually working.
        // Iterable<? extends StaticError> errors = fortress.compile(file);

        //        if (errors.iterator().hasNext()) {
        //            for (StaticError error: errors) { System.err.println(error); }
        //        } else {
        // If there are no errors, all components will have been written to disk by the FileBasedRepository.
        // We also need to write the corresponding APIs.
        for (Component component: _components) {
            Api corresponding = (Api)component.accept(ApiMaker.ONLY);
            IndexBuilder.ApiResult result = IndexBuilder.buildApis(file.lastModified(), corresponding);

            if (result.isSuccessful()) {
                fileBasedRepository.addApis(result.apis());
            } else {
                for (StaticError error: result.errors()) { System.err.println(error); }
            }
        }
        //        }
    }

    /* Helper method that creates a CompilationUnit from a Fortress source file name.*/
    private Option<CompilationUnit> makeCompilationUnit(String fileName) throws UserError {
        File sourceFile = new File(fileName);

        if (! sourceFile.exists()) {
            throw new UserError("Error: File " + fileName + " does not exist.");
        }
        try {
            return ASTIO.parseToJavaAst(fileName);
        }
        catch (IOException e) {
            throw new ShellException(e);
        }
    }

    /* Helper method that returns true if a component of the given name is installed.*/
    private boolean isInstalled(String componentName) {
        return new File(shell.getComponents() + SEP + "components" + SEP + componentName + SEP + ".jst").exists();
    }
}