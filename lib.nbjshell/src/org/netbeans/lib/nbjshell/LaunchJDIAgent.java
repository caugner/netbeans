/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2016 Oracle and/or its affiliates. All rights reserved.
 *
 * Oracle and Java are registered trademarks of Oracle and/or its affiliates.
 * Other names may be trademarks of their respective owners.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common
 * Development and Distribution License("CDDL") (collectively, the
 * "License"). You may not use this file except in compliance with the
 * License. You can obtain a copy of the License at
 * http://www.netbeans.org/cddl-gplv2.html
 * or nbbuild/licenses/CDDL-GPL-2-CP. See the License for the
 * specific language governing permissions and limitations under the
 * License.  When distributing the software, include this License Header
 * Notice in each file and include the License file at
 * nbbuild/licenses/CDDL-GPL-2-CP.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the GPL Version 2 section of the License file that
 * accompanied this code. If applicable, add the following below the
 * License Header, with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * If you wish your version of this file to be governed by only the CDDL
 * or only the GPL Version 2, indicate your decision by adding
 * "[Contributor] elects to include this software in this distribution
 * under the [CDDL or GPL Version 2] license." If you do not indicate a
 * single choice of license, a recipient has the option to distribute
 * your version of this file under either the CDDL, the GPL Version 2 or
 * to extend the choice of license to its licensees as provided above.
 * However, if you add GPL Version 2 code and therefore, elected the GPL
 * Version 2 license, then the option applies only if the new code is
 * made subject to such option by the copyright holder.
 *
 * Contributor(s):
 *
 * Portions Copyrighted 2016 Sun Microsystems, Inc.
 */
package org.netbeans.lib.nbjshell;

import com.sun.jdi.BooleanValue;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.InvalidTypeException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import jdk.jshell.execution.JdiDefaultExecutionControl;
import jdk.jshell.execution.JdiExecutionControl;
import jdk.jshell.execution.JdiInitiator;
import jdk.jshell.execution.Util;
import jdk.jshell.spi.ExecutionControl;
import jdk.jshell.spi.ExecutionEnv;
import org.netbeans.api.java.platform.JavaPlatform;
import org.netbeans.api.project.Project;

/**
 * Launches a JShell VM using standard JDI agent, but incorporates
 * a customized agent class.
 *
 * @author sdedic
 */
// PENDING: JDIExecutionControl does not bring that much - copy over and derive
// from NbExecutionControlBase to provide an uniform API
public class LaunchJDIAgent extends JdiExecutionControl
    implements ExecutionControl, RemoteJShellService, NbExecutionControl{

    private static final Logger LOG = Logger.getLogger(LaunchJDIAgent.class.getName());

    private static final String REMOTE_AGENT =  "org.netbeans.lib.jshell.agent.AgentWorker"; // NOI18N

    protected final ObjectInput in;
    protected final ObjectOutput out;

    public LaunchJDIAgent(ObjectOutput out, ObjectInput in, VirtualMachine vm) {
        super(out, in);
        this.in = in;
        this.out = out;
        this.vm = vm;
    }

    /**
     * Create an instance.
     *
     * @param cmdout the output for commands
     * @param cmdin the input for responses
     */
    private LaunchJDIAgent(ObjectOutput cmdout, ObjectInput cmdin,
            VirtualMachine vm, Process process, List<Consumer<String>> deathListeners) {
        this(cmdout, cmdin, vm);
        this.process = process;
        deathListeners.add(s -> disposeVM());
    }

    protected VirtualMachine vm;
    private Process process;

    private final Object STOP_LOCK = new Object();
    private boolean userCodeRunning = false;
    private boolean closed = false;

    @Override
    public void closeStreams() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        try {
            if (out != null) {
                out.close();
            }
            if (in != null) {
                in.close();
            }
        } catch (IOException ex) {
            // ignore
        }
    }
    
    protected void notifyClosed() {
        closeStreams();
    }

    public Map<String, String> commandVersionInfo() {
        Map<String, String> result = new HashMap<>();
        try {
            Object o = extensionCommand("nb_vmInfo", null);
            if (!(o instanceof Map)) {
                return Collections.emptyMap();
            }
            result = (Map<String, String>)o;
        } catch (RunException | InternalException ex) {
            LOG.log(Level.INFO, "Error invoking JShell agent", ex.toString());
        } catch (EngineTerminationException ex) {
            notifyClosed();
        }
        return result;
    }

    /**
     * Returns the agent's object reference obtained from the debugger.
     * May return null, so the {@link #sendStopUserCode()} will stop the first
     * running agent it finds.
     * 
     * @return the target agent's reference
     */
    protected ObjectReference  getAgentObjectReference() {
        return null;
    }
    
    @Override
    public boolean requestShutdown() {
        disposeVM();
        return true;
    }
    
    public boolean isClosed() {
        return vm == null;
    }

    @Override
    public String getTargetSpec() {
        return null;
    }

    /**
     * Creates an ExecutionControl instance based on a JDI
     * {@code LaunchingConnector}.
     *
     * @return the generator
     */
    public static ExecutionControl.Generator launch(JavaPlatform platform) {
        return env -> create(platform, env, true);
    }

    /**
     * Creates an ExecutionControl instance based on a JDI
     * {@code ListeningConnector} or {@code LaunchingConnector}.
     *
     * Initialize JDI and use it to launch the remote JVM. Set-up a socket for
     * commands and results. This socket also transports the user
     * input/output/error.
     *
     * @param env the context passed by
     * {@link jdk.jshell.spi.ExecutionControl#start(jdk.jshell.spi.ExecutionEnv) }
     * @return the channel
     * @throws IOException if there are errors in set-up
     */
    private static JdiExecutionControl create(JavaPlatform platform, ExecutionEnv env, boolean isLaunch) throws IOException {
        try (final ServerSocket listener = new ServerSocket(0)) {
            // timeout after 60 seconds
            listener.setSoTimeout(60000);
            int port = listener.getLocalPort();

            Map<String, String> customArguments = null;
            
            if (platform != null) {
                String jHome = platform.getSystemProperties().get("java.home");
                customArguments = new HashMap<>();
                // TODO: if jHome is null for some reason, the connector fails.
                customArguments.put("home", jHome);
            }
            // Set-up the JDI connection
            JdiInitiator jdii = new JdiInitiator(port,
                    env.extraRemoteVMOptions(), REMOTE_AGENT, isLaunch, null, JdiDefaultExecutionControl.defaultTimeout(), customArguments);
            VirtualMachine vm = jdii.vm();
            Process process = jdii.process();
            
            List<Consumer<String>> deathListeners = new ArrayList<>();
            deathListeners.add(s -> env.closeDown());
            
            vm.resume();

            // Set-up the commands/reslts on the socket.  Piggy-back snippet
            // output.
            Socket socket = listener.accept();
            // out before in -- match remote creation so we don't hang
            Map<String, OutputStream> io = new HashMap<>();
            CloseFilter outFilter = new CloseFilter(env.userOut());
            io.put("out", outFilter);
            io.put("err", env.userErr());

            /*
            class L implements BiFunction<ObjectInput, ObjectOutput, ExecutionControl> {
                LaunchJDIAgent  agent;
                
                ExecutionControl forward() throws IOException {
                    return Util.remoteInputOutput(
                        socket.getInputStream(), 
                        socket.getOutputStream(),
                        io,
                        null, this);
                }

                @Override
                public ExecutionControl apply(ObjectInput cmdIn, ObjectOutput cmdOut) {
                    agent = new LaunchJDIAgent(cmdout, cmdIn, vm, process, deathListeners);
                    return agent;
                }
                
            }
            */

            LaunchJDIAgent agent = (LaunchJDIAgent)
                    Util.remoteInputOutput(
                        socket.getInputStream(), 
                        socket.getOutputStream(),
                        io,
                        Collections.emptyMap(), 
                        (ObjectInput cmdIn, ObjectOutput cmdOut) ->
                                new LaunchJDIAgent(cmdOut, cmdIn, vm, process, deathListeners)
                    );
            Util.detectJdiExitEvent(vm, s -> {
                for (Consumer<String> h : deathListeners) {
                    h.accept(s);
                }
                agent.disposeVM();
            });
            outFilter.agent = agent;
            return agent;
        }
    }
    
    static class CloseFilter extends FilterOutputStream {
        volatile LaunchJDIAgent agent;
        
        public CloseFilter(OutputStream out) {
            super(out);
        }

        @Override
        public void close() throws IOException {
            super.close();
            if (agent != null) {
                agent.notifyClosed();
            }
        }
    }

    @Override
    public void setClasspath(String path) throws EngineTerminationException, InternalException {
        if (!suppressClasspath) {
            super.setClasspath(path); 
        }
    }

    @Override
    public void addToClasspath(String path) throws EngineTerminationException, InternalException {
        if (!suppressClasspath) {
            super.addToClasspath(path);
        }
    }
    
    

    @Override
    public String invoke(String classname, String methodname)
            throws ExecutionControl.RunException,
            ExecutionControl.EngineTerminationException, ExecutionControl.InternalException {
        String res;
        synchronized (STOP_LOCK) {
            userCodeRunning = true;
        }
        try {
            res = super.invoke(classname, methodname);
        } finally {
            synchronized (STOP_LOCK) {
                userCodeRunning = false;
            }
        }
        return res;
    }
    
    protected boolean isUserCodeRunning() {
        return userCodeRunning;
    }
    
    protected Object getLock() {
        return STOP_LOCK;
    }

    /**
     * Interrupts a running remote invoke by manipulating remote variables
     * and sending a stop via JDI.
     *
     * @throws EngineTerminationException the execution engine has terminated
     * @throws InternalException an internal problem occurred
     */
    @Override
    public void stop() throws ExecutionControl.EngineTerminationException, ExecutionControl.InternalException {
        synchronized (STOP_LOCK) {
            if (!userCodeRunning) {
                return;
            }

            vm().suspend();
            try {
                OUTER:
                for (ThreadReference thread : vm().allThreads()) {
                    // could also tag the thread (e.g. using name), to find it easier
                    for (StackFrame frame : thread.frames()) {
                        if (REMOTE_AGENT.equals(frame.location().declaringType().name()) &&
                                (    "invoke".equals(frame.location().method().name())
                                || "varValue".equals(frame.location().method().name()))) {
                            ObjectReference thiz = frame.thisObject();
                            com.sun.jdi.Field inClientCode = thiz.referenceType().fieldByName("inClientCode");
                            com.sun.jdi.Field expectingStop = thiz.referenceType().fieldByName("expectingStop");
                            com.sun.jdi.Field stopException = thiz.referenceType().fieldByName("stopException");
                            if (((BooleanValue) thiz.getValue(inClientCode)).value()) {
                                thiz.setValue(expectingStop, vm().mirrorOf(true));
                                ObjectReference stopInstance = (ObjectReference) thiz.getValue(stopException);

                                vm().resume();
                                debug("Attempting to stop the client code...\n");
                                thread.stop(stopInstance);
                                thiz.setValue(expectingStop, vm().mirrorOf(false));
                            }

                            break OUTER;
                        }
                    }
                }
            } catch (ClassNotLoadedException | IncompatibleThreadStateException | InvalidTypeException ex) {
                throw new ExecutionControl.InternalException("Exception on remote stop: " + ex);
            } finally {
                vm().resume();
            }
        }
    }

    @Override
    public void close() {
        super.close();
        disposeVM();
    }

    private synchronized void disposeVM() {
        if (process != null) {
            try {
                if (vm != null) {
                    vm.dispose(); // This could NPE, so it is caught below
                    vm = null;
                }
            } catch (VMDisconnectedException ex) {
                // Ignore if already closed
            } catch (Throwable ex) {
                debug(ex, "disposeVM");
            } finally {
                if (process != null) {
                    process.destroy();
                    process = null;
                }
            }
        } else {
            vm = null;
        }
    }

    @Override
    protected synchronized VirtualMachine vm() throws ExecutionControl.EngineTerminationException {
        if (vm == null) {
            throw new ExecutionControl.EngineTerminationException("VM closed");
        } else {
            return vm;
        }
    }

    /**
     * Log debugging information. Arguments as for {@code printf}.
     *
     * @param format a format string as described in Format string syntax
     * @param args arguments referenced by the format specifiers in the format
     * string.
     */
    private static void debug(String format, Object... args) {
        // Reserved for future logging
    }

    /**
     * Log a serious unexpected internal exception.
     *
     * @param ex the exception
     * @param where a description of the context of the exception
     */
    private static void debug(Throwable ex, String where) {
        // Reserved for future logging
    }
    
    private boolean suppressClasspath;

    @Override
    public void suppressClasspathChanges(boolean b) {
        this.suppressClasspath = b;
    }

    @Override
    public ExecutionControlException getBrokenException() {
        return null;
    }

}
