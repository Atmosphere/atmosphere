import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import {
  View,
  Text,
  TextInput,
  FlatList,
  TouchableOpacity,
  KeyboardAvoidingView,
  Platform,
  SafeAreaView,
  StyleSheet,
  StatusBar,
  Image,
} from 'react-native';
import { registerRootComponent } from 'expo';
import Markdown from 'react-native-markdown-display';
import NetInfo from '@react-native-community/netinfo';
import {
  setupReactNative,
  AtmosphereProvider,
  useStreamingRN,
} from 'atmosphere.js/react-native';

// --- Initialize atmosphere.js for React Native ---
const caps = setupReactNative({ netInfo: NetInfo });
console.log('Atmosphere RN capabilities:', caps);

// --- Configuration ---
// Point this at your running spring-boot-ai-classroom server.
// For Expo Go on a physical device, use your machine's LAN IP.
// For emulator: Android = 10.0.2.2, iOS simulator = localhost.
const SERVER_URL = Platform.select({
  android: 'http://10.0.2.2:8080',
  default: 'http://localhost:8080',
});

// --- Types ---
interface UserMessage {
  role: 'user';
  text: string;
}

interface AssistantMessage {
  role: 'assistant';
  text: string;
  complete: boolean;
}

type Message = UserMessage | AssistantMessage;

interface Room {
  id: string;
  label: string;
  icon: string;
  color: string;
}

// Atmosphere brand palette — warm gold/brown from the logo
const ATMOSPHERE = {
  gold: '#B8963E',        // primary gold from logo
  goldLight: '#D4AF5C',   // lighter gold accent
  goldDark: '#8B6F2E',    // darker brown-gold
  bgDark: '#1A1408',      // deep warm black
  bgMedium: '#2A2010',    // warm dark brown
  bgCard: 'rgba(184,150,62,0.10)', // subtle gold tint
  textPrimary: '#FFF8E7', // warm white
  textSecondary: 'rgba(255,248,231,0.6)',
  textMuted: 'rgba(255,248,231,0.4)',
};

const ROOMS: Room[] = [
  { id: 'math', label: 'Math', icon: '\u03C0', color: '#D4AF5C' },
  { id: 'code', label: 'Code', icon: '<>', color: '#B8963E' },
  { id: 'science', label: 'Science', icon: '\u269B', color: '#C9A84C' },
  { id: 'general', label: 'General', icon: '?', color: '#8B6F2E' },
];

// --- Room Selector Screen ---
function RoomSelector({ onJoin }: { onJoin: (room: string) => void }) {
  return (
    <SafeAreaView style={styles.selectorContainer}>
      <StatusBar barStyle="light-content" />
      <Image
        source={require('./assets/atmosphere-logo.png')}
        style={styles.logo}
        resizeMode="contain"
      />
      <Text style={styles.selectorTitle}>AI Classroom</Text>
      <Text style={styles.selectorSubtitle}>
        Join a room. Ask a question. Every student sees the AI response stream
        in real time.
      </Text>
      <View style={styles.roomGrid}>
        {ROOMS.map((room) => (
          <TouchableOpacity
            key={room.id}
            style={[styles.roomCard, { borderColor: room.color }]}
            onPress={() => onJoin(room.id)}
            activeOpacity={0.7}
          >
            <Text style={styles.roomIcon}>{room.icon}</Text>
            <Text style={styles.roomLabel}>{room.label}</Text>
          </TouchableOpacity>
        ))}
      </View>
    </SafeAreaView>
  );
}

// --- Classroom Screen ---
function Classroom({
  room,
  onLeave,
}: {
  room: string;
  onLeave: () => void;
}) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const flatListRef = useRef<FlatList>(null);
  const roomConfig = ROOMS.find((r) => r.id === room);

  const request = useMemo(
    () => ({
      url: `${SERVER_URL}/atmosphere/classroom/${room}`,
      transport: 'websocket' as const,
      fallbackTransport: 'long-polling' as const,
      reconnect: true,
      maxReconnectOnClose: 10,
      reconnectInterval: 5000,
      trackMessageLength: true,
      enableProtocol: false,
      contentType: 'application/json',
    }),
    [room],
  );

  const {
    fullText,
    isStreaming,
    progress,
    stats,
    error,
    isConnected,
    send,
    reset,
  } = useStreamingRN({ request });

  // Update assistant message as tokens arrive
  useEffect(() => {
    if (!fullText) return;
    setMessages((prev) => {
      const last = prev[prev.length - 1];
      if (last && last.role === 'assistant' && !last.complete) {
        return [
          ...prev.slice(0, -1),
          { role: 'assistant', text: fullText, complete: false },
        ];
      }
      return [...prev, { role: 'assistant', text: fullText, complete: false }];
    });
  }, [fullText]);

  // Mark assistant message complete when streaming stops
  useEffect(() => {
    if (!isStreaming && fullText) {
      setMessages((prev) => {
        const last = prev[prev.length - 1];
        if (last && last.role === 'assistant' && !last.complete) {
          return [...prev.slice(0, -1), { ...last, complete: true }];
        }
        return prev;
      });
    }
  }, [isStreaming, fullText]);

  // Auto-scroll to bottom
  useEffect(() => {
    if (messages.length > 0) {
      setTimeout(() => flatListRef.current?.scrollToEnd({ animated: true }), 100);
    }
  }, [messages, fullText]);

  const handleSend = useCallback(() => {
    const text = input.trim();
    if (!text || isStreaming) return;
    setMessages((prev) => [...prev, { role: 'user', text }]);
    setInput('');
    reset();
    send(text);
  }, [input, isStreaming, reset, send]);

  const renderMessage = useCallback(
    ({ item }: { item: Message }) => {
      if (item.role === 'user') {
        return (
          <View
            style={[
              styles.userBubble,
              { backgroundColor: roomConfig?.color ?? '#667eea' },
            ]}
          >
            <Text style={styles.userText}>{item.text}</Text>
          </View>
        );
      }
      return (
        <View style={styles.assistantBubble}>
          <View style={styles.markdownWrap}>
            <Markdown style={markdownStyles}>{item.text}</Markdown>
          </View>
          {!item.complete && <Text style={styles.cursor}>|</Text>}
        </View>
      );
    },
    [roomConfig],
  );

  return (
    <SafeAreaView style={styles.classroomContainer}>
      <StatusBar barStyle="light-content" />

      {/* Header */}
      <View style={styles.header}>
        <View style={styles.headerLeft}>
          <Text style={styles.headerTitle}>AI Classroom</Text>
          <View
            style={[
              styles.roomBadge,
              { backgroundColor: roomConfig?.color ?? '#667eea' },
            ]}
          >
            <Text style={styles.roomBadgeText}>
              {roomConfig?.label ?? room}
            </Text>
          </View>
        </View>
        <TouchableOpacity onPress={onLeave} style={styles.leaveButton}>
          <Text style={styles.leaveButtonText}>Leave</Text>
        </TouchableOpacity>
      </View>

      {/* Connection status */}
      {!isConnected && (
        <View style={styles.offlineBanner}>
          <Text style={styles.offlineBannerText}>
            Offline - waiting for network...
          </Text>
        </View>
      )}

      {/* Progress / error */}
      {progress && (
        <View style={styles.progressBanner}>
          <Text style={styles.progressText}>{progress}</Text>
        </View>
      )}
      {error && (
        <View style={styles.errorBanner}>
          <Text style={styles.errorText}>{error}</Text>
        </View>
      )}

      {/* Messages */}
      <KeyboardAvoidingView
        style={styles.messagesContainer}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        keyboardVerticalOffset={Platform.OS === 'ios' ? 90 : 0}
      >
        <FlatList
          ref={flatListRef}
          data={messages}
          renderItem={renderMessage}
          keyExtractor={(_item, index) => String(index)}
          contentContainerStyle={styles.messagesList}
          ListEmptyComponent={
            <View style={styles.emptyContainer}>
              <Text style={styles.emptyIcon}>{roomConfig?.icon}</Text>
              <Text style={styles.emptyText}>
                Ask a {roomConfig?.label.toLowerCase()} question to get started
              </Text>
            </View>
          }
        />

        {/* Stats */}
        {stats && !isStreaming && (
          <View style={styles.statsBar}>
            <Text style={styles.statsText}>
              {stats.totalTokens} tokens &middot; {stats.elapsedMs}ms &middot;{' '}
              {stats.tokensPerSecond.toFixed(1)} tok/s
            </Text>
          </View>
        )}

        {/* Input */}
        <View style={styles.inputContainer}>
          <TextInput
            style={styles.textInput}
            value={input}
            onChangeText={setInput}
            placeholder={`Ask a ${roomConfig?.label.toLowerCase() ?? ''} question...`}
            placeholderTextColor="rgba(255,255,255,0.4)"
            editable={!isStreaming}
            onSubmitEditing={handleSend}
            returnKeyType="send"
            blurOnSubmit={false}
          />
          <TouchableOpacity
            style={[
              styles.sendButton,
              { backgroundColor: roomConfig?.color ?? '#667eea' },
              (isStreaming || !input.trim()) && styles.sendButtonDisabled,
            ]}
            onPress={handleSend}
            disabled={isStreaming || !input.trim()}
          >
            <Text style={styles.sendButtonText}>Send</Text>
          </TouchableOpacity>
        </View>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

// --- Root App ---
function App() {
  const [room, setRoom] = useState<string | null>(null);

  return (
    <AtmosphereProvider config={{ logLevel: 'info' }}>
      {room ? (
        <Classroom room={room} onLeave={() => setRoom(null)} />
      ) : (
        <RoomSelector onJoin={setRoom} />
      )}
    </AtmosphereProvider>
  );
}

export default App;
registerRootComponent(App);

// --- Markdown styles for assistant messages ---
const markdownStyles = StyleSheet.create({
  body: { color: ATMOSPHERE.textPrimary, fontSize: 15, lineHeight: 22 },
  strong: { color: ATMOSPHERE.goldLight, fontWeight: '700' },
  em: { color: ATMOSPHERE.textSecondary, fontStyle: 'italic' },
  code_inline: {
    backgroundColor: 'rgba(184,150,62,0.2)',
    color: ATMOSPHERE.goldLight,
    paddingHorizontal: 5,
    paddingVertical: 2,
    borderRadius: 4,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    fontSize: 13,
  },
  fence: {
    backgroundColor: 'rgba(0,0,0,0.3)',
    borderColor: ATMOSPHERE.goldDark,
    borderWidth: 1,
    borderRadius: 8,
    padding: 12,
    marginVertical: 8,
  },
  code_block: {
    color: ATMOSPHERE.goldLight,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    fontSize: 13,
  },
  heading1: { color: ATMOSPHERE.gold, fontSize: 20, fontWeight: '700', marginVertical: 6 },
  heading2: { color: ATMOSPHERE.gold, fontSize: 18, fontWeight: '700', marginVertical: 5 },
  heading3: { color: ATMOSPHERE.goldLight, fontSize: 16, fontWeight: '600', marginVertical: 4 },
  bullet_list: { marginVertical: 4 },
  ordered_list: { marginVertical: 4 },
  list_item: { color: ATMOSPHERE.textPrimary, marginVertical: 2 },
  link: { color: ATMOSPHERE.goldLight, textDecorationLine: 'underline' },
  blockquote: {
    borderLeftWidth: 3,
    borderLeftColor: ATMOSPHERE.gold,
    paddingLeft: 12,
    marginVertical: 6,
    backgroundColor: 'rgba(184,150,62,0.06)',
  },
  paragraph: { marginVertical: 4 },
  hr: { borderColor: ATMOSPHERE.goldDark, borderWidth: 0.5, marginVertical: 8 },
});

// --- Styles ---
const styles = StyleSheet.create({
  // Logo
  logo: {
    width: 80,
    height: 80,
    marginBottom: 16,
  },

  // Room Selector
  selectorContainer: {
    flex: 1,
    backgroundColor: ATMOSPHERE.bgDark,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 20,
  },
  selectorTitle: {
    fontSize: 32,
    fontWeight: '700',
    color: ATMOSPHERE.gold,
    marginBottom: 8,
  },
  selectorSubtitle: {
    fontSize: 14,
    color: ATMOSPHERE.textSecondary,
    textAlign: 'center',
    marginBottom: 40,
    maxWidth: 320,
    lineHeight: 20,
  },
  roomGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'center',
    gap: 16,
  },
  roomCard: {
    backgroundColor: ATMOSPHERE.bgCard,
    borderWidth: 2,
    borderRadius: 16,
    paddingVertical: 28,
    paddingHorizontal: 32,
    alignItems: 'center',
    minWidth: 140,
  },
  roomIcon: {
    fontSize: 36,
    color: ATMOSPHERE.textPrimary,
    marginBottom: 8,
  },
  roomLabel: {
    fontSize: 18,
    fontWeight: '600',
    color: ATMOSPHERE.textPrimary,
  },

  // Classroom
  classroomContainer: {
    flex: 1,
    backgroundColor: ATMOSPHERE.bgMedium,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: ATMOSPHERE.bgDark,
  },
  headerLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  headerTitle: {
    fontSize: 18,
    fontWeight: '700',
    color: ATMOSPHERE.gold,
  },
  roomBadge: {
    paddingHorizontal: 10,
    paddingVertical: 3,
    borderRadius: 10,
  },
  roomBadgeText: {
    fontSize: 12,
    fontWeight: '600',
    color: ATMOSPHERE.bgDark,
  },
  leaveButton: {
    backgroundColor: 'rgba(184,150,62,0.2)',
    paddingHorizontal: 14,
    paddingVertical: 6,
    borderRadius: 8,
  },
  leaveButtonText: {
    fontSize: 13,
    color: ATMOSPHERE.goldLight,
  },

  // Banners
  offlineBanner: {
    backgroundColor: '#e74c3c',
    paddingVertical: 6,
    paddingHorizontal: 16,
    alignItems: 'center',
  },
  offlineBannerText: {
    color: '#fff',
    fontSize: 12,
    fontWeight: '600',
  },
  progressBanner: {
    backgroundColor: ATMOSPHERE.bgCard,
    paddingVertical: 6,
    paddingHorizontal: 16,
  },
  progressText: {
    color: ATMOSPHERE.textSecondary,
    fontSize: 12,
    fontStyle: 'italic',
  },
  errorBanner: {
    backgroundColor: 'rgba(231,76,60,0.2)',
    paddingVertical: 6,
    paddingHorizontal: 16,
  },
  errorText: {
    color: '#e74c3c',
    fontSize: 12,
  },

  // Messages
  messagesContainer: {
    flex: 1,
  },
  messagesList: {
    paddingHorizontal: 16,
    paddingVertical: 12,
    flexGrow: 1,
  },
  userBubble: {
    alignSelf: 'flex-end',
    backgroundColor: ATMOSPHERE.gold,
    padding: 12,
    borderRadius: 16,
    borderBottomRightRadius: 4,
    maxWidth: '85%',
    marginBottom: 8,
  },
  userText: {
    color: ATMOSPHERE.bgDark,
    fontSize: 15,
    lineHeight: 21,
  },
  assistantBubble: {
    alignSelf: 'flex-start',
    backgroundColor: ATMOSPHERE.bgCard,
    padding: 12,
    borderRadius: 16,
    borderBottomLeftRadius: 4,
    maxWidth: '85%',
    marginBottom: 8,
    flexDirection: 'row',
  },
  markdownWrap: {
    flex: 1,
  },
  assistantText: {
    color: ATMOSPHERE.textPrimary,
    fontSize: 15,
    lineHeight: 21,
    flex: 1,
  },
  cursor: {
    color: ATMOSPHERE.goldLight,
    fontSize: 15,
    marginLeft: 2,
  },

  // Empty state
  emptyContainer: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 60,
  },
  emptyIcon: {
    fontSize: 48,
    color: ATMOSPHERE.textMuted,
    marginBottom: 12,
  },
  emptyText: {
    color: ATMOSPHERE.textMuted,
    fontSize: 15,
    textAlign: 'center',
  },

  // Stats
  statsBar: {
    paddingVertical: 4,
    paddingHorizontal: 16,
  },
  statsText: {
    color: ATMOSPHERE.textMuted,
    fontSize: 11,
  },

  // Input
  inputContainer: {
    flexDirection: 'row',
    padding: 12,
    gap: 8,
    backgroundColor: ATMOSPHERE.bgDark,
  },
  textInput: {
    flex: 1,
    backgroundColor: 'rgba(184,150,62,0.12)',
    borderRadius: 12,
    paddingHorizontal: 16,
    paddingVertical: 10,
    color: ATMOSPHERE.textPrimary,
    fontSize: 15,
  },
  sendButton: {
    borderRadius: 12,
    paddingHorizontal: 20,
    justifyContent: 'center',
  },
  sendButtonDisabled: {
    opacity: 0.4,
  },
  sendButtonText: {
    color: ATMOSPHERE.bgDark,
    fontWeight: '600',
    fontSize: 15,
  },
});
