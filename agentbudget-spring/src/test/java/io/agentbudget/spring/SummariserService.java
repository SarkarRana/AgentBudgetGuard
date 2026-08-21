package io.agentbudget.spring;

import io.agentbudget.core.GuardedResult;
import io.agentbudget.core.TokenUsage;

/**
 * The interface the proxy is built against — JDK dynamic proxies need one, and requiring it is
 * how this module avoids class proxying and the bytecode machinery that comes with it.
 */
interface SummariserService {

    String summarise(String sessionId, long tokens);

    String summariseWithoutSession(long tokens);

    String summariseFixedSession(long tokens);

    String unbudgeted(long tokens);

    String throwsChecked(String sessionId) throws Exception;

    GuardedResult<String> summariseReturningUsage(String sessionId, TokenUsage usage);

    String summariseTwice(String sessionId, long first, long second);

    String selfInvoking(String sessionId, long tokens);

    /**
     * Which method bodies actually ran. On the interface rather than the implementation because
     * the bean is a JDK proxy: there is no concrete type to autowire, which is itself the proof
     * that this module never asks for class proxying.
     */
    java.util.List<String> executed();
}
