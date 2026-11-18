package com.tvkouame.studio;

/**
 * Liste des IA gratuites, écrite directement dans le code (aucune variable d'environnement).
 * L'application essaie les fournisseurs dans l'ordre de cette liste. Si l'un échoue (panne, limite atteinte,
 * clé refusée, délai dépassé), elle passe automatiquement au suivant et met le fautif en pause quelques minutes.
 *
 * Clé : NO_KEY = le service marche sans clé ; "" = vide, le fournisseur est ignoré tant que tu n'as pas collé ta clé
 * gratuite (la ligne indique où l'obtenir) ; toute autre valeur est envoyée comme clé.
 */
public final class AiConfig {
    private AiConfig() {}

    public static final String NO_KEY = "-";

    static final class Provider {
        final String name, endpoint, key;
        final String[] models;
        Provider(String name, String endpoint, String key, String... models) {
            this.name = name; this.endpoint = endpoint; this.key = key; this.models = models;
        }
        boolean enabled() { return key != null && key.length() > 0; }
    }

    // ---- Clés personnelles gratuites : colle-les ici (inscription gratuite, sans carte bancaire) ----
    public static final String LLM7_KEY = "unused";        // clé gratuite perso (limites plus hautes) : https://token.llm7.io
    public static final String KILO_KEY = "anonymous";     // ou clé de compte : https://kilo.ai
    public static final String GEMINI_KEY = "";            // https://aistudio.google.com/apikey
    public static final String GROQ_KEY = "";              // https://console.groq.com/keys
    public static final String OPENROUTER_KEY = "";        // https://openrouter.ai/keys (modèles « :free »)
    public static final String MISTRAL_KEY = "";           // https://console.mistral.ai/api-keys

    /** Ordre d'essai : d'abord ceux qui marchent sans rien, puis ceux pour lesquels tu as collé une clé. */
    static final Provider[] PROVIDERS = {
            new Provider("LLM7", "https://api.llm7.io/v1/chat/completions", LLM7_KEY, "default", "fast"),
            new Provider("Kilo AI", "https://api.kilo.ai/api/gateway/chat/completions", KILO_KEY,
                    "kilo-auto/free", "google/gemma-4-26b-a4b-it:free"),
            new Provider("OVHcloud", "https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/chat/completions", NO_KEY,
                    "gpt-oss-20b", "Llama-3.3-70B-Instruct"),
            new Provider("Pollinations", "https://text.pollinations.ai/openai", NO_KEY, "openai-fast"),
            new Provider("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", GEMINI_KEY,
                    "gemini-2.5-flash", "gemini-2.5-flash-lite"),
            new Provider("Groq", "https://api.groq.com/openai/v1/chat/completions", GROQ_KEY,
                    "llama-3.3-70b-versatile", "llama-3.1-8b-instant"),
            new Provider("OpenRouter", "https://openrouter.ai/api/v1/chat/completions", OPENROUTER_KEY, "openrouter/free"),
            new Provider("Mistral", "https://api.mistral.ai/v1/chat/completions", MISTRAL_KEY, "mistral-small-latest"),
    };

    public static final int CONNECT_TIMEOUT_MS = 8000;
    public static final int READ_TIMEOUT_MS = 35000;
    /** Pause d'un fournisseur en panne ou saturé (limite de requêtes, erreur serveur, délai). */
    public static final long COOLDOWN_MS = 2 * 60 * 1000L;
    /** Pause d'un fournisseur dont la clé est refusée (401/403). */
    public static final long BAD_KEY_COOLDOWN_MS = 30 * 60 * 1000L;
}
