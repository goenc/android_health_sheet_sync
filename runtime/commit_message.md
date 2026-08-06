設定画面を常時スクロール可能に修正

理由:
設定項目が少ない状態でも設定画面の下部へ到達できるようにするため

変更点:
- 設定画面を開いている間は常にルート列を上下スクロール可能に変更
- 記録一覧の表示状態に依存していたスクロール制御を削除

確認内容:
- `gradlew.bat :app:testDebugUnitTest` 成功
- `gradlew.bat :app:assembleDebug` 成功
- `gradlew.bat :app:lintDebug` 成功
- `git diff --check` 成功（改行警告のみ）
- 接続端末へデバッグAPKをインストールし、メイン画面の起動成功を確認
- 起動後の致命的例外なし
