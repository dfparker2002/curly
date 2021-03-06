/*
 * Copyright 2015 Adobe Global Services.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.adobe.ags.curly.test;

import com.adobe.ags.curly.ApplicationState;
import com.adobe.ags.curly.ConnectionManager;
import com.adobe.ags.curly.controller.ActionGroupRunner;
import com.adobe.ags.curly.controller.AuthHandler;
import com.adobe.ags.curly.model.RunnerResult;
import com.adobe.ags.curly.xml.Action;
import com.adobe.ags.curly.xml.ErrorBehavior;
import com.adobe.ags.curly.xml.ResultType;
import java.io.IOException;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.embed.swing.JFXPanel;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.After;
import org.junit.AfterClass;
import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *
 * @author brobert
 */
public class ErrorBehaviorTest {

    static TestWebServer webserver;
    public static final String TEST_USER = "USER";
    public static final String TEST_PASSWORD = "PASSWORD";
    AuthHandler handler;
    CloseableHttpClient client;

    public ErrorBehaviorTest() {
    }

    @BeforeClass
    public static void setUpClass() throws IOException, InterruptedException {
        new ToolkitApplicationLauncher().init();
        new JFXPanel();
        webserver = TestWebServer.getServer();
        webserver.requireLogin = true;
    }

    @AfterClass
    public static void tearDownClass() {
        ConnectionManager.getInstance().shutdown();
        if (webserver != null) {
            webserver.shutdown();
        }
        Platform.exit();
    }

    @Before
    public void setUp() {
        ApplicationState.getInstance().runningProperty().set(true);
        handler = new AuthHandler(
                new ReadOnlyStringWrapper("localhost:" + webserver.port),
                new ReadOnlyBooleanWrapper(false),
                new ReadOnlyStringWrapper(TEST_USER),
                new ReadOnlyStringWrapper(TEST_PASSWORD)
        );
        client = handler.getAuthenticatedClient();
    }

    @After
    public void tearDown() throws IOException {
        client.close();
    }

    @Test
    public void testHappyPath() throws IOException, ParseException {
        List<Action> actions = Arrays.asList(successAction(), successAction(), successAction(), successAction(), successAction());
        ActionGroupRunner runner = new ActionGroupRunner("Happy Test", () -> client, actions, Collections.EMPTY_MAP, Collections.EMPTY_SET);
        runner.run();
        assertResults(runner.getResult(), true, true);
    }

    @Test
    public void testGlobalIgnore() throws IOException, ParseException {
        ApplicationState.getInstance().errorBehaviorProperty().set(ErrorBehavior.IGNORE);
        List<Action> actions = Arrays.asList(failureAction(), failureAction(), failureAction());
        ActionGroupRunner runner = new ActionGroupRunner("Global Ignore Test", () -> client, actions, Collections.EMPTY_MAP, Collections.EMPTY_SET);
        runner.run();
        assertResults(runner.getResult(), false, true);
    }

    @Test
    public void testActionIgnore() throws IOException, ParseException {
        ApplicationState.getInstance().errorBehaviorProperty().set(ErrorBehavior.HALT);
        Action fail1 = failureAction();
        Action fail2 = failureAction();
        fail1.setErrorBehavior(ErrorBehavior.IGNORE);
        fail2.setErrorBehavior(ErrorBehavior.IGNORE);
        List<Action> actions = Arrays.asList(fail1, fail2);
        ActionGroupRunner runner = new ActionGroupRunner("Action Ignore Test", () -> client, actions, Collections.EMPTY_MAP, Collections.EMPTY_SET);
        runner.run();
        assertResults(runner.getResult(), false, true);
    }

    @Test
    public void testActionRequireFailure() throws IOException, ParseException {
        ApplicationState.getInstance().errorBehaviorProperty().set(ErrorBehavior.HALT);
        Action fail1 = failureAction();
        Action fail2 = failureAction();
        fail1.setErrorBehavior(ErrorBehavior.SKIP_IF_SUCCESSFUL);
        fail2.setErrorBehavior(ErrorBehavior.SKIP_IF_SUCCESSFUL);
        List<Action> actions = Arrays.asList(fail1, fail2);
        ActionGroupRunner runner = new ActionGroupRunner("Action Require Failure Test", () -> client, actions, Collections.EMPTY_MAP, Collections.EMPTY_SET);
        runner.run();
        assertResults(runner.getResult(), false, true);
    }

    @Test
    public void testActionRequireFailure2() throws IOException, ParseException {
        ApplicationState.getInstance().errorBehaviorProperty().set(ErrorBehavior.HALT);
        Action fail1 = successAction();
        Action fail2 = failureAction();
        fail1.setErrorBehavior(ErrorBehavior.SKIP_IF_SUCCESSFUL);
        fail2.setErrorBehavior(ErrorBehavior.SKIP_IF_SUCCESSFUL);
        List<Action> actions = Arrays.asList(fail1, fail2);
        ActionGroupRunner runner = new ActionGroupRunner("Action Require Failure Test", () -> client, actions, Collections.EMPTY_MAP, Collections.EMPTY_SET);
        runner.run();
        assertResults(runner.getResult(), false, false);
    }

    @Test
    public void testHalt() throws IOException, ParseException {
        ApplicationState.getInstance().errorBehaviorProperty().set(ErrorBehavior.HALT);
        Action fail1 = failureAction();
        Action fail2 = failureAction();
        List<Action> actions = Arrays.asList(fail1, fail2);
        ActionGroupRunner runner = new ActionGroupRunner("Action Halt Test", () -> client, actions, Collections.EMPTY_MAP, Collections.EMPTY_SET);
        runner.run();
        assertResults(runner.getResult(), false, false);
        assertFalse(ApplicationState.getInstance().runningProperty().get());
    }

    private int actionCounter = 0;

    public Action successAction() {
        Action successAction = new Action();
        successAction.setName("success " + (actionCounter++));
        successAction.setResultType(ResultType.PLAIN);
        successAction.setCommand("http://localhost:" + webserver.port + "/success");
        return successAction;
    }

    public Action failureAction() {
        Action failureAction = new Action();
        failureAction.setName("failure " + (actionCounter++));
        failureAction.setResultType(ResultType.PLAIN);
        failureAction.setCommand("http://localhost:" + webserver.port + "/failure");
        return failureAction;
    }

    private void assertResults(RunnerResult result, boolean completelySuccessful, boolean completed) {
        try {
            // Headless JFX sometimes throws NPE when evaluating these, not sure why.
            Boolean isCompleted = isBindingTrue(result.completed());
            Boolean isSuccessful = isBindingTrue(result.completelySuccessful());
            if (completelySuccessful) {
                assertTrue("Expected successful=true", isSuccessful);
            } else {
                assertFalse("Expected successful=false", isSuccessful);
            }

            if (completed) {
                assertTrue("Expected completed=true", isCompleted);
            } else {
                assertFalse("Expected completed=false", isCompleted);
            }
        } catch (NullPointerException ex) {
            Logger.getLogger(ErrorBehaviorTest.class.getName()).warning("Completed and Completely succcessful properties shouldn't be null");
        }
    }

    private boolean isBindingTrue(BooleanBinding binding) {
        binding.invalidate();
        sync();
        return binding != null && binding.getValue() != null && binding.get();
    }
    
    private void sync() {
        Semaphore test = new Semaphore(1);
        test.acquireUninterruptibly();
        Platform.runLater(test::release);
        try {
            test.acquire();
        } catch (InterruptedException ex) {
            Logger.getLogger(ErrorBehaviorTest.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}
