package com.elioo.healthcare.medicalreport.adapter.in.router;

import com.elioo.healthcare.medicalreport.adapter.in.handler.MedicalReportChatHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Router configuration for medical report chat APIs.
 *
 * <p>Architecture: Inbound Adapter (Web) in Hexagonal Architecture</p>
 * <ul>
 *   <li>Defines HTTP routes for chat operations</li>
 *   <li>Maps routes to handler methods</li>
 *   <li>Uses functional reactive routing (not @RequestMapping)</li>
 * </ul>
 *
 * <p>Base Path: /api/v1/medical-report/chat</p>
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>POST /{reportId}/send - Send chat message and get AI response</li>
 *   <li>GET /{reportId}/messages - Get conversation history</li>
 *   <li>DELETE /{reportId}/clear - Clear conversation history</li>
 * </ul>
 *
 * <p>Design Decisions:</p>
 * <ul>
 *   <li>RESTful design with reportId as path parameter</li>
 *   <li>POST for send (creates new messages)</li>
 *   <li>GET for retrieve (read-only)</li>
 *   <li>DELETE for clear (removes data)</li>
 *   <li>All responses in JSON format</li>
 * </ul>
 */
@Configuration
public class MedicalReportChatRouter {

    private static final String BASE_PATH = "/api/v1/medical-report/chat";

    /**
     * Define chat routes.
     *
     * <p>Route Structure:</p>
     * <pre>
     * POST   /api/v1/medical-report/chat/{reportId}/send     -> sendMessage()
     * GET    /api/v1/medical-report/chat/{reportId}/messages -> getMessages()
     * DELETE /api/v1/medical-report/chat/{reportId}/clear    -> clearConversation()
     * </pre>
     */
    @Bean
    public RouterFunction<ServerResponse> chatRoutes(MedicalReportChatHandler handler) {
        return RouterFunctions.route()
                // Send chat message
                .POST(BASE_PATH + "/{reportId}/send", handler::sendMessage)

                // Get conversation history
                .GET(BASE_PATH + "/{reportId}/messages", handler::getMessages)

                // Clear conversation
                .DELETE(BASE_PATH + "/{reportId}/clear", handler::clearConversation)

                .build();
    }
}
