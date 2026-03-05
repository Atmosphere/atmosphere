import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

export default defineConfig({
  site: 'https://async-io.org',
  base: '/docs',
  integrations: [
    starlight({
      title: 'Atmosphere',
      description: 'The Java framework for WebSocket, SSE, and AI streaming',
      logo: {
        src: './src/assets/logo.svg',
        replacesTitle: false,
      },
      social: [
        { icon: 'github', label: 'GitHub', href: 'https://github.com/Atmosphere/atmosphere' },
      ],
      editLink: {
        baseUrl: 'https://github.com/Atmosphere/atmosphere/edit/main/docs/',
      },
      customCss: ['./src/styles/custom.css'],
      head: [
        { tag: 'link', attrs: { rel: 'icon', href: '/docs/favicon.svg', type: 'image/svg+xml' } },
      ],
      sidebar: [
        {
          label: 'Getting Started',
          items: [
            { label: 'Welcome', slug: 'welcome' },
            { label: "What's New in 4.0", slug: 'whats-new' },
          ],
        },
        {
          label: 'Tutorial',
          items: [
            {
              label: 'I — Foundations',
              collapsed: false,
              items: [
                { label: '1. Introduction', slug: 'tutorial/01-introduction' },
                { label: '2. Getting Started', slug: 'tutorial/02-getting-started' },
                { label: '3. @ManagedService', slug: 'tutorial/03-managed-service' },
                { label: '4. Transports', slug: 'tutorial/04-transports' },
              ],
            },
            {
              label: 'II — Core Features',
              collapsed: false,
              items: [
                { label: '5. Broadcaster & Pub/Sub', slug: 'tutorial/05-broadcaster' },
                { label: '6. Rooms & Presence', slug: 'tutorial/06-rooms' },
                { label: '7. WebSocket Deep Dive', slug: 'tutorial/07-websocket' },
                { label: '8. Interceptors', slug: 'tutorial/08-interceptors' },
              ],
            },
            {
              label: 'III — AI Platform',
              collapsed: false,
              items: [
                { label: '9. @AiEndpoint & Streaming', slug: 'tutorial/09-ai-endpoint' },
                { label: '10. @AiTool', slug: 'tutorial/10-ai-tools' },
                { label: '11. AI Adapters', slug: 'tutorial/11-ai-adapters' },
                { label: '12. AI Filters & Routing', slug: 'tutorial/12-ai-filters' },
                { label: '13. MCP Server', slug: 'tutorial/13-mcp' },
              ],
            },
            {
              label: 'IV — Production',
              collapsed: false,
              items: [
                { label: '14. Spring Boot', slug: 'tutorial/14-spring-boot' },
                { label: '15. Quarkus', slug: 'tutorial/15-quarkus' },
                { label: '16. Clustering', slug: 'tutorial/16-clustering' },
                { label: '17. Durable Sessions', slug: 'tutorial/17-durable-sessions' },
                { label: '18. Observability', slug: 'tutorial/18-observability' },
                { label: '19. atmosphere.js Client', slug: 'tutorial/19-client' },
                { label: '20. gRPC & Kotlin', slug: 'tutorial/20-grpc-kotlin' },
              ],
            },
          ],
        },
        {
          label: 'Reference',
          items: [
            { label: 'Core Runtime', slug: 'reference/core' },
            { label: 'Rooms & Presence', slug: 'reference/rooms' },
            { label: 'AI / LLM', slug: 'reference/ai' },
            { label: 'MCP Server', slug: 'reference/mcp' },
            { label: 'gRPC Transport', slug: 'reference/grpc' },
            { label: 'Kotlin DSL', slug: 'reference/kotlin' },
            { label: 'Observability', slug: 'reference/observability' },
            { label: 'Durable Sessions', slug: 'reference/durable-sessions' },
          ],
        },
        {
          label: 'Integrations',
          items: [
            { label: 'Spring Boot', slug: 'integrations/spring-boot' },
            { label: 'Quarkus', slug: 'integrations/quarkus' },
            { label: 'Spring AI', slug: 'integrations/spring-ai' },
            { label: 'LangChain4j', slug: 'integrations/langchain4j' },
            { label: 'Google ADK', slug: 'integrations/adk' },
            { label: 'Embabel', slug: 'integrations/embabel' },
          ],
        },
        {
          label: 'Infrastructure',
          items: [
            { label: 'Redis Clustering', slug: 'infrastructure/redis' },
            { label: 'Kafka Clustering', slug: 'infrastructure/kafka' },
          ],
        },
        {
          label: 'Client Libraries',
          items: [
            { label: 'atmosphere.js', slug: 'clients/javascript' },
            { label: 'wAsync (Java)', slug: 'clients/java' },
            { label: 'React Native', slug: 'clients/react-native' },
          ],
        },
      ],
    }),
  ],
});
