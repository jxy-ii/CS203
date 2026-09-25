package mygrant.notifications;

import java.time.Duration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

/**
 * A chat client reserved for impact assessment, separate from the shared one RAG uses.
 * Assessment runs on the policy ingestion request, so it needs a short timeout of its own:
 * a global HTTP timeout would also cut off RAG answers and Federal Register downloads.
 * It also must not retry, because Spring AI's default retries a timed-out call up to ten
 * times with back-off, turning one slow answer into minutes of blocked ingestion.
 */
@Configuration
class ImpactAssessmentConfig {

    @Bean
    ChatClient impactAssessmentChatClient(
            @Value("${spring.ai.ollama.base-url}") String baseUrl,
            @Value("${spring.ai.ollama.chat.model}") String model,
            @Value("${mygrant.impact-assessment.connect-timeout:2s}") Duration connectTimeout,
            @Value("${mygrant.impact-assessment.read-timeout:6s}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
        // JSON mode constrains llama3.2 to emit a JSON object, and a zero temperature keeps
        // the same profile and policy from being rated differently on each run.
        OllamaChatModel chatModel = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(OllamaOptions.builder().model(model).format("json").temperature(0.0).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();
        return ChatClient.create(chatModel);
    }
}
