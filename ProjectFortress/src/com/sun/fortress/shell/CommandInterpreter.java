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

package com.sun.fortress.shell;

import java.io.*;
import java.util.regex.Pattern;
import java.util.*;
import edu.rice.cs.plt.tuple.Option;

import com.sun.fortress.compiler.*;
import com.sun.fortress.compiler.index.*;
import com.sun.fortress.interpreter.drivers.*;
import com.sun.fortress.nodes.CompilationUnit;
import com.sun.fortress.nodes.Component;
import com.sun.fortress.nodes.Api;

import static com.sun.fortress.shell.ConvenientStrings.*; 

import java.io.*;

public class CommandInterpreter {
    private Shell shell;
    
    CommandInterpreter(Shell _shell) { 
        shell = _shell;
    }
        
    void compile(String fileName) throws UserError, InterruptedException, IOException {
        try {
            FortressRepository fileBasedRepository = new FileBasedRepository(shell.getPwd());
            Fortress fortress = new Fortress(fileBasedRepository);
        
            Iterable<? extends StaticError> errors = fortress.compile(new File(fileName));
        
            for (StaticError error: errors) { System.err.println(error); }
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
        Driver.evalComponent(Option.unwrap(makeCompilationUnit(fileName))); 
    }
    
    void run(String fileName) throws UserError, IOException, Throwable {
        Driver.runProgram(Option.unwrap(Driver.readJavaAst(fileName)), new ArrayList<String>());
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
                IndexBuilder.ApiResult result = IndexBuilder.buildApis(corresponding);
                
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
            return Driver.parseToJavaAst(fileName);
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
    