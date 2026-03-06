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
              label: 'Getting Started',
              collapsed: false,
              items: [
                { label: 'Introduction', slug: 'tutorial/01-introduction' },
                { label: 'First App', slug: 'tutorial/02-getting-started' },
              ],
            },
            {
              label: 'AI & LLM Streaming',
              collapsed: false,
              items: [
                { label: '@AiEndpoint & Streaming', slug: 'tutorial/09-ai-endpoint' },
                { label: '@AiTool', slug: 'tutorial/10-ai-tools' },
                { label: 'AI Adapters', slug: 'tutorial/11-ai-adapters' },
                { label: 'AI Filters & Routing', slug: 'tutorial/12-ai-filters' },
                { label: 'MCP Server', slug: 'tutorial/13-mcp' },
              ],
            },
            {
              label: 'Core Concepts',
              collapsed: false,
              items: [
                { label: '@ManagedService', slug: 'tutorial/03-managed-service' },
                { label: 'Transports', slug: 'tutorial/04-transports' },
                { label: 'Broadcaster & Pub/Sub', slug: 'tutorial/05-broadcaster' },
                { label: 'Rooms & Presence', slug: 'tutorial/06-rooms' },
              ],
            },
            {
              label: 'Advanced',
              collapsed: true,
              items: [
                { label: 'WebSocket Deep Dive', slug: 'tutorial/07-websocket' },
                { label: 'Interceptors', slug: 'tutorial/08-interceptors' },
                { label: 'atmosphere.js Client', slug: 'tutorial/19-client' },
                { label: 'gRPC & Kotlin', slug: 'tutorial/20-grpc-kotlin' },
              ],
            },
            {
              label: 'Deployment',
              collapsed: true,
              items: [
                { label: 'Spring Boot', slug: 'tutorial/14-spring-boot' },
                { label: 'Quarkus', slug: 'tutorial/15-quarkus' },
                { label: 'WAR Deployment', slug: 'tutorial/21-war-deployment' },
                { label: 'Clustering', slug: 'tutorial/16-clustering' },
                { label: 'Durable Sessions', slug: 'tutorial/17-durable-sessions' },
                { label: 'Observability', slug: 'tutorial/18-observability' },
                { label: 'Migration 2.x → 4.0', slug: 'tutorial/22-migration' },
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
