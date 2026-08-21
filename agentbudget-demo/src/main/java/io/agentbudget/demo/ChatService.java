package io.agentbudget.demo;

/**
 * Split from its implementation because {@code @Budgeted}'s advice is JDK dynamic proxies only
 * (no CGLIB, no bytecode manipulation -- see {@code agentbudget-spring}'s README) and a JDK
 * proxy needs an interface to implement.
 */
public interface ChatService {

    String chat(String sessionId, String prompt);
}
