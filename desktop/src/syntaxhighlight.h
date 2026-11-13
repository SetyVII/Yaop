#pragma once

#include <QSyntaxHighlighter>
#include <QStringList>
#include <QTextFormat>
#include <QList>

// Syntax highlighting for code blocks. Pure QtGui: no external grammar files,
// no new dependencies. A code block is a single QTextBlock whose lines are
// separated by U+2028, tagged with these block-format properties.
namespace pkm::syntax {
constexpr int BlockStyleProperty = QTextFormat::UserProperty + 705;
constexpr int CodeLanguageProperty = QTextFormat::UserProperty + 706;

struct Language {
    QString id, label;
};
// Supported language ids: "cpp", "java", "bash", "go". "" means plain text.
const QList<Language> &languages();
QString normalize(const QString &id);   // accepts aliases (c++, sh, golang…); "" if unknown
QString label(const QString &id);
}

class CodeSyntaxHighlighter final : public QSyntaxHighlighter {
  public:
    explicit CodeSyntaxHighlighter(QTextDocument *document);
    void setDark(bool dark);
    void setLanguage(const QString &language);
  protected:
    void highlightBlock(const QString &text) override;
  private:
    bool dark_ = false;
    QString m_language;
};
