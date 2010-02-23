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

package com.sun.fortress.repository;

import com.sun.fortress.useful.Path;
import com.sun.fortress.useful.StringMap;
import com.sun.fortress.useful.Useful;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class ProjectProperties {

    private static String fortressAutoHome() {
        String s = null;
        try {
            s = System.getProperty("fortress.autohome");
        }
        catch (Throwable th) {

        }
        if (s == null) {
            s = System.getenv("FORTRESS_AUTOHOME");
        }
        if (s == null) {
            Path p = new Path(System.getProperty("java.class.path"));
            File f = null;
            try {
                f = p.findDir("../ProjectFortress");
                try {
                    s = (new File(f, "..")).getCanonicalPath();
                }
                catch (IOException ex) {
                    throw new Error("Failure to evaluate relative path .. from " + f);
                }
            }
            catch (FileNotFoundException ex1) {
                try {
                    f = p.findDir("../../ProjectFortress/build");
                    try {
                        s = (new File(f, "../..")).getCanonicalPath();
                    }
                    catch (IOException ex) {
                        throw new Error("Failure to evaluate relative path ../.. from " + f);
                    }
                }
                catch (FileNotFoundException ex2) {
                    throw new Error(
                            "Could not find fortress home using fortress.autohome, FORTRESS_AUTOHOME, or probing classpath.");
                }
            }
        }
        return s;
    }


    /**
     * If the property is defined, return that value,
     * else return BASE_DIR + default_name .
     *
     * @param property_name
     * @param default_name
     * @return
     */

    private static String someImplDir(String property_name, String default_name) {
        String s = null;

        try {
            s = System.getProperty(property_name);
        }
        catch (Throwable th) {

        }

        if (s == null) {
            s = BASEDIR + default_name;
        }

        s = s + File.separator;

        // It is a myth that Windows requires backslashes.  Only the DOS shell
        // requires backslashes.
        s = backslashToSlash(s);
        return s;
    }


    /**
     * No-op right now.
     * <p/>
     * Intended to replace backslashes in a string with slashes.
     * It is a myth that Windows needs backslashes in path names;
     * in fact, it is only the DOS shell.  This fix was backed out
     * because a different fix for the same problem was added; however,
     * once tested this may be re-enabled.
     *
     * @param s
     */
    public static String backslashToSlash(String s) {
        //return s.replace("\\", "/");
        return s;
    }

    public static final String FORTRESS_AUTOHOME = fortressAutoHome(); // MUST PRECEDE FORTRESS_HOME

    static final String home = System.getenv("HOME");

    /**
     * The search path for the repository, which will provide configuration information, etc.
     */

    static final StringMap searchHead = new StringMap.ComposedMaps(new StringMap.FromReflection(ProjectProperties.class,
                                                                                                "FORTRESS_"),
                                                                   new StringMap.FromSysProps(),
                                                                   new StringMap.FromEnv());

    private static final String explicitRepository = searchHead.get("fortress.repository");
    public static final String REPOSITORY_PATH_STRING =
            explicitRepository != null ? explicitRepository : searchHead.getCompletely(
                    ";.fortress;${HOME}/.fortress;${FORTRESS_AUTOHOME}/local_repository;${FORTRESS_AUTOHOME}/default_repository",
                    1000);
    public static final Path REPOSITORY_PATH = new Path(REPOSITORY_PATH_STRING);

    static {
        if (REPOSITORY_PATH.length() == 0) {
            if (explicitRepository != null) throw new Error(
                    "User-specified repository " + explicitRepository + " is not a readable directory");
            else throw new Error(
                    "Could not find any readable directories in the (semicolon-separated) repository list " +
                    REPOSITORY_PATH_STRING);
        }
    }

    public static final String REPOSITORY = REPOSITORY_PATH.findDirName(".", "").toString();

    static {
        if ("".equals(REPOSITORY)) {
            throw new Error("Could not find any readable directories in the (semicolon-separated) repository list " +
                            REPOSITORY_PATH_STRING);
        }

        File config = new File(REPOSITORY + "/configuration");
        File otherConfig = null;
        try {
            otherConfig = REPOSITORY_PATH.findFile("configuration");
        }
        catch (IOException ex) {
            throw new Error(ex.getMessage());
        }
        if (otherConfig != null) {
            if (!config.exists()) {
                throw new Error("Repository " + REPOSITORY + " does not contain configuration" + " but " + otherConfig +
                                " is ok ");
            } else if (!config.isFile()) {
                throw new Error(
                        "Repository " + REPOSITORY + "/configuration is not a regular file" + " but " + otherConfig +
                        " is ok ");
            } else if (!config.canRead()) {
                throw new Error("Repository " + REPOSITORY + "/configuration is not readable" + " but " + otherConfig +
                                " is ok ");
            }
        } else {
            if (!config.exists()) {
                throw new Error("Repository " + REPOSITORY + " does not contain configuration");
            } else if (!config.isFile()) {
                throw new Error("Repository " + REPOSITORY + "/configuration is not a regular file");
            } else if (!config.canRead()) {
                throw new Error("Repository " + REPOSITORY + "/configuration is not readable");
            }
        }
    }

    static final StringMap searchTail = new StringMap.FromFileProps(REPOSITORY + "/configuration");

    static final StringMap allProps = new StringMap.ComposedMaps(searchHead, searchTail);

    static final public String get(String s) {
        if (s == null) return s;
        String result = Useful.substituteVarsCompletely(s, allProps, 1000);
        return result;
    }

    /**
     * Get a String property or environment variable.
     * Use property.naming.conventions, these are automatically
     * translated to ENVIRONMENT_NAMING_CONVENTIONS for environment
     * variables and fields of ProjectProperties itself.
     *
     * @param s
     * @param ifMissing
     * @return
     */
    static final public String get(String s, String ifMissing) {
        String result = allProps.get(s);
        if (result == null) result = ifMissing;
        if (result != null) result = Useful.substituteVarsCompletely(result, allProps, 1000);
        if (result == null) {
            throw new Error(
                    "Must supply a definition (as property, environment variable, or repository configuration property) for " +
                    s);
        }
        return result;
    }

    /**
     * Get a boolean property or environment variable.
     * Use property.naming.conventions, these are automatically
     * translated to ENVIRONMENT_NAMING_CONVENTIONS for environment
     * variables and fields of ProjectProperties itself.
     *
     * @param s
     * @param ifMissing
     * @return
     */
    static final public boolean getBoolean(String s, boolean ifMissing) {
        String result = allProps.get(s);
        if (result != null) result = Useful.substituteVarsCompletely(result, allProps, 1000);
        if (result == null) return ifMissing;
        if (result.length() == 0) return true;
        s = result.toLowerCase();
        char c = result.charAt(0);
        if (c == 'y' || c == 't' || c == '1') return true;
        if (c == 'n' || c == 'f' || c == '0') return false;

        throw new Error("Unexpected definition of prop/env " + s + ", got " + result + ", need t/f/y/n/1/0[...]");
    }

    /**
     * Searches for property/environment definition in the following order
     * <p/>
     * System.getProperty("fortress.cache")
     * System.getenv("FORTRESS_CACHE")
     * ./.fortress/configuration .getProperty("fortress.cache")
     * ~/.fortress/configuration .getProperty("fortress.cache")
     * ${FORTRESS_HOME}/local_repository/configuration .getProperty("fortress.cache") .
     * ${FORTRESS_HOME}/default_repository/configuration .getProperty("fortress.cache") .
     */

    private static String searchDef(String asProp, String asEnv, String defaultValue) {
        String result = null;
        result = System.getProperty(asProp);
        if (result == null) result = System.getenv(asEnv);
        if (result == null) result = searchTail.get(asProp);
        result = result == null ? defaultValue : result;
        result = Useful.substituteVarsCompletely(result, allProps, 1000);
        return result;
    }


    /**
     * This static field holds the absolute path of the (sub)project location, as
     * computed by reflectively finding the file location of the unnamed
     * package, and grabbing the parent directory.
     * <p/>
     * The path name includes a trailing slash!
     */
    public static final String BASEDIR = searchDef("BASEDIR", "BASEDIR", "${FORTRESS_AUTOHOME}/ProjectFortress/");

    public static final String ROOTDIR = searchDef("ROOTDIR", "ROOTDIR", "${FORTRESS_AUTOHOME}/");

    public static final String CACHES = get("fortress.caches", "${REPOSITORY}/caches");

    public static final String INTERPRETER_CACHE_DIR = get("fortress.interpreter.cache", "${CACHES}/interpreter_cache");
    public static final String INTERPRETER_PARSED_CACHE_DIR = get("fortress.interpreter.parsed.cache",
                                                                  "${CACHES}/interpreter_parsed_cache");
    public static final String PRESYNTAX_CACHE_DIR = get("fortress.presyntax.cache", "${CACHES}/presyntax_cache");
    public static final String ANALYZED_CACHE_DIR = get("fortress.analyzed.cache", "${CACHES}/analyzed_cache");
    public static final String ANALYZED_CACHE_DEPENDS_DIR = get("fortress.analyzed.cache.depends",
                                                                "${CACHES}/analyzed_cache/depends");
    public static final String SYNTAX_CACHE_DIR = get("fortress.syntax.cache", "${CACHES}/syntax_cache");
    public static final String ENVIRONMENT_CACHE_DIR = get("fortress.environment.cache", "${CACHES}/environment_cache");
    public static final String BYTECODE_CACHE_DIR = get("fortress.bytecode.cache", "${CACHES}/bytecode_cache");
    public static final String NATIVE_WRAPPER_CACHE_DIR = get("fortress.nativewrapper.cache",
                                                              "${CACHES}/nativewrapper_cache");

    public static final Path SOURCE_PATH = new Path(searchDef("fortress.source.path", "FORTRESS_SOURCE_PATH", "."));


    static {
        ensureDirectoryExists(PRESYNTAX_CACHE_DIR);
        ensureDirectoryExists(INTERPRETER_CACHE_DIR);
        ensureDirectoryExists(INTERPRETER_PARSED_CACHE_DIR);
        ensureDirectoryExists(ANALYZED_CACHE_DIR);
        ensureDirectoryExists(ANALYZED_CACHE_DEPENDS_DIR);
        ensureDirectoryExists(SYNTAX_CACHE_DIR);
        ensureDirectoryExists(ENVIRONMENT_CACHE_DIR);
        ensureDirectoryExists(BYTECODE_CACHE_DIR);
        ensureDirectoryExists(NATIVE_WRAPPER_CACHE_DIR);
    }


    public static String ensureDirectoryExists(String s) throws Error {
        File f = new File(s);
        if (f.exists()) {
            if (f.isDirectory()) {
                // ok
            } else {
                throw new Error("Necessary 'directory' " + s + " is not a directory");
            }
        } else {
            if (f.mkdirs()) {
                // ok
            } else {
                throw new Error("Failed to create directory " + s);
            }
        }
        return s;
    }

    public final static String COMP_SOURCE_SUFFIX = "fss";

    public final static String COMP_TREE_SUFFIX = "tfs";

    public final static String API_SOURCE_SUFFIX = "fsi";

    public final static String API_TREE_SUFFIX = "tfi";

    public final static boolean leakCheck = getBoolean("fortress.test.leaks", false);


    /**
     * Creates a new instance of ProjectProperties
     */
    private ProjectProperties() {
    }


    // public static String astSuffixForSource(String s) {
    //     if (s.endsWith("." + COMP_SOURCE_SUFFIX))
    //         return COMP_TREE_SUFFIX;

    //     if (s.endsWith("." + API_SOURCE_SUFFIX))
    //         return API_TREE_SUFFIX;

    //     throw new Error("Unexpected suffix on Fortress(?) source file");
    // }

    public static String fileName(String dir, String name, String suffix) {
        return dir + "/" + name + "." + suffix;
    }

    public static String compFileName(String dir, String name) {
        return fileName(dir, name, ProjectProperties.COMP_TREE_SUFFIX);
    }

    public static String apiFileName(String dir, String name) {
        return fileName(dir, name, ProjectProperties.API_TREE_SUFFIX);
    }


    /**
     * Get an int property or environment variable.
     * Use property.naming.conventions, these are automatically
     * translated to ENVIRONMENT_NAMING_CONVENTIONS for environment
     * variables and fields of ProjectProperties itself.
     * Integers default to base 10, but a different base can be chosen,
     * for example DEADBEEF_16 .
     *
     * @param s
     * @param ifMissing
     * @return
     */
    public static int getInt(String s, int ifMissing) {
        String result = allProps.get(s);
        if (result != null) result = Useful.substituteVarsCompletely(result, allProps, 1000);
        if (result == null) return ifMissing;
        if (result.length() == 0) return ifMissing;
        int base = 10;
        int underat = result.indexOf('_');
        if (underat != -1) {
            base = Integer.parseInt(result.substring(underat + 1));
            result = result.substring(0, underat);
        }
        return Integer.parseInt(result, base);
    }


    public static long getLong(String s, long ifMissing) {
        String result = allProps.get(s);
        if (result != null) result = Useful.substituteVarsCompletely(result, allProps, 1000);
        if (result == null) return ifMissing;
        if (result.length() == 0) return ifMissing;
        int base = 10;
        int underat = result.indexOf('_');
        if (underat != -1) {
            base = Integer.parseInt(result.substring(underat + 1));
            result = result.substring(0, underat);
        }
        return Long.parseLong(result, base);
    }


    public static StringMap composedWith(StringMap props) {
        return new StringMap.ComposedMaps(props, allProps);
    }
}
