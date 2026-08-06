腹囲グラフの基準上限を80cmへ変更

理由:
腹囲グラフの基準値を現在の90cmから80cmへ変更し、同じ描画領域で数値スケールを見直すため

変更点:
- 腹囲グラフの基準上限を90cmから80cmへ変更
- グラフ上下の描画位置比率は変更せず、基準線・目盛り・値変換へ新しい上限値を反映

確認内容:
- `gradlew.bat :app:testDebugUnitTest` 成功
- `gradlew.bat :app:assembleDebug` 成功
- `gradlew.bat :app:lintDebug` 成功
- `git diff --check` 成功（改行警告のみ）
- 接続端末へデバッグAPKをインストールし、メイン画面の起動成功を確認
- 起動後の致命的例外なし
