(ns chat.server
  (:require [org.httpkit.server :as http]
            [compojure.core :refer [defroutes GET]]
            [compojure.route :as route]
            [ring.util.response :as response]
            [clojure.core.async :refer [go <! chan put!]]
            [wkok.openai-clojure.api :as openai]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; 存储所有WebSocket客户端的通道
(def clients (atom #{}))

;; 广播消息给所有客户端
(defn broadcast! [msg]
  (doseq [client @clients]
    (http/send! client (json/write-str msg))))

(def chat-history (atom [{:role "system"
                          :content "你是聊天室的一员，回应其他成员的信息，略带幽默，要简洁，不要超过三句话"}]))

(defn ws-handler [req]
  (http/with-channel req channel
    ;; 客户端连接时加入
    (swap! clients conj channel)
    (println "Client connected. Total clients:" (count @clients))

    ;; 客户端断开时移除
    (http/on-close channel
                   (fn [_]
                     (swap! clients disj channel)
                     (println "Client disconnected. Total clients:" (count @clients))))

    ;; 收到消息时处理
    (http/on-receive channel
                     (fn [data]
                       (let [msg (json/read-str data :key-fn keyword)
                             text (:text msg)
                             from (:from msg)
                             ;; 创建历史快照 (history-snapshot 此时包含初始的 system prompt)
                             history-snapshot @chat-history
                             llm-chan (chan)]

                         ;; 保存用户消息到历史
                         ;; chat-history 将变为 [{:role "system"...}, ..., {:role "user", :content "michael: text"}]
                         (swap! chat-history conj {:role (if (= from "michael") "user" "assistant")
                                                   :content (str from ": " text)})

                         ;; 广播用户消息
                         (broadcast! {:from from :text text})

                         ;; OpenAI 调用
                         (go
                           (try
                             (let [;; history-snapshot 已经包含了 system prompt
                                   ;; 我们只需要加入当前用户的消息 text
                                   messages-for-openai (conj history-snapshot {:role "user" :content text})
                                   resp (openai/create-chat-completion
                                         {:model "gpt-4.1"
                                          :messages messages-for-openai
                                          :stream false})]
                               (println "OpenAI response:" resp)
                               (when-let [content (get-in resp [:choices 0 :message :content])]
                                 (broadcast! {:from "openai" :text content})
                                 ;; --- 链式对话的关键点 (1) ---
                                 ;; 如果希望 Grok 能看到 OpenAI 的回复，需要将 OpenAI 的回复也加入 chat-history
                                 ;; (swap! chat-history conj {:role "assistant" :content (str "openai: " content)})
                                 (put! llm-chan :openai-done)))
                             (catch Exception e
                               (println "OpenAI error:" (.getMessage e))
                               (put! llm-chan :openai-error))))

                         ;; Grok 调用（等待 OpenAI 完成）
                         (go
                           (println "Waiting for OpenAI to complete for Grok...")
                           (<! llm-chan) ; 等 ಈ OpenAI ಪೂರ್ಣಗೊಂಡಿದೆ
                           (println "Starting Grok call...")
                           (try
                             (let [;; @chat-history 此时已经包含:
                                   ;; 1. 初始的 system prompt
                                   ;; 2. 用户之前的对话 (如果有)
                                   ;; 3. 当前用户的消息
                                   ;; 4. (如果上面链式对话(1)被取消注释) OpenAI 的回复
                                   ;; 所以 messages-for-grok 直接就是 @chat-history
                                   messages-for-grok @chat-history
                                   resp (openai/create-chat-completion
                                         {:model "grok-3-beta"
                                          :messages messages-for-grok
                                          :stream false}
                                         {:api-key (System/getenv "GROK_API_KEY")
                                          :api-endpoint "https://api.x.ai/v1"})]
                               (println "Grok response:" resp)
                               (when-let [content (get-in resp [:choices 0 :message :content])]
                                 (broadcast! {:from "grok" :text content})
                                 ;; --- 链式对话的关键点 (2) ---
                                 ;; 如果希望 DeepSeek 能看到 Grok 的回复，需要将 Grok 的回复也加入 chat-history
                                 ;; (swap! chat-history conj {:role "assistant" :content (str "grok: " content)})
                                 (put! llm-chan :grok-done)))
                             (catch Exception e
                               (println "Grok error:" (.getMessage e))
                               (put! llm-chan :grok-error))))

                         ;; DeepSeek 调用（等待 Grok 完成）
;; DeepSeek 调用（等待 Grok 完成）
(go
  (println "Waiting for Grok to complete for DeepSeek...")
  (<! llm-chan)
  (println "Starting DeepSeek call...")
  (try
    (let [;; 假设 messages-for-deepseek 是这样获取的，
          ;; 正如我们之前讨论的最佳实践 (chat-history 已包含 system prompt 和当前用户消息)
          messages-for-deepseek @chat-history]

      ;; ----------- 非常重要的诊断步骤开始 -----------
      (println "========== MESSAGES BEING SENT TO DEEPSEEK START ==========")
      (clojure.pprint/pprint messages-for-deepseek) ; 使用 pprint 格式化打印，方便阅读
      (println "========== MESSAGES BEING SENT TO DEEPSEEK END ==========")
      ;; ----------- 非常重要的诊断步骤结束 -----------

      (let [resp (openai/create-chat-completion
                   {:model "deepseek-chat"
                    :messages messages-for-deepseek ; 确保这里用的是上面打印的那个变量
                    :stream false}
                   {:api-key (System/getenv "DEEPSEEK_API_KEY")
                    :api-endpoint "https://api.deepseek.com/v1"})]
        (println "DeepSeek response:" resp)
        (when-let [content (get-in resp [:choices 0 :message :content])]
          (broadcast! {:from "deepseek" :text content}))))
    (catch Exception e
      (println "DeepSeek error:" (.getMessage e))))))))))


                         ;; 定义路由
                         (defroutes app-routes
                           (GET "/" []
                             (let [file-path (io/file "resources/public/index.html")
                                   file (when (.exists file-path) (response/file-response (.getPath file-path)))]
                               (println "Serving index.html, file exists:" (.exists file-path) ", response:" file)
                               (if file
                                 file
                                 {:status 404 :body "Index file not found"})))
                           (GET "/ws" req ws-handler)
                           (route/resources "/")
                           (route/not-found "Not Found"))

                         ;; 启动服务器
                         (defn -main [& args]
                           (println "Starting server on port 3000")
                           (http/run-server app-routes {:port 3000})
                           (println "Chat server running on port 3000"))
