//! Deterministic, domain-independent observed keyphrases. No semantic claim is fabricated.
use std::collections::{BTreeMap, BTreeSet};
use unicode_segmentation::UnicodeSegmentation;

type Candidate = (String, Vec<(u32, u32)>, f64);

pub(crate) struct Keyphrase {
    pub canonical: String,
    pub label: String,
    pub mentions: Vec<(u32, u32)>,
}

pub(crate) fn keyphrases(text: &str) -> Vec<Keyphrase> {
    let stop: BTreeSet<&str> = STOPWORDS.split_whitespace().collect();
    let words: Vec<_> = text
        .unicode_word_indices()
        .map(|(start, word)| (start, word, word.to_lowercase()))
        .collect();
    let mut candidates: BTreeMap<String, Candidate> = BTreeMap::new();
    for length in 1..=3 {
        for window in words.windows(length) {
            if window.iter().any(|(_, word, canonical)| {
                canonical.chars().count() < 3
                    || stop.contains(canonical.as_str())
                    || !word.chars().any(char::is_alphabetic)
            }) {
                continue;
            }
            if window.windows(2).any(|pair| {
                text[pair[0].0 + pair[0].1.len()..pair[1].0]
                    .chars()
                    .any(|c| !c.is_whitespace() && c != '-')
            }) {
                continue;
            }
            let canonical = window
                .iter()
                .map(|(_, _, canonical)| canonical.as_str())
                .collect::<Vec<_>>()
                .join(" ");
            let start = window[0].0;
            let last = &window[length - 1];
            let end = last.0 + last.1.len();
            let label = text[start..end]
                .split_whitespace()
                .collect::<Vec<_>>()
                .join(" ");
            let entry = candidates
                .entry(canonical)
                .or_insert_with(|| (label, Vec::new(), 0.0));
            entry.1.push((start as u32, end as u32));
            entry.2 = length as f64 + entry.1.len() as f64 * 2.0;
        }
    }
    let mut ranked: Vec<_> = candidates.into_iter().collect();
    ranked.sort_by(|a, b| b.1 .2.total_cmp(&a.1 .2).then_with(|| a.0.cmp(&b.0)));
    // Retain 12 keyphrases for search/mention UI; graph edge fan-out is independently bounded.
    ranked
        .into_iter()
        .take(12)
        .map(|(canonical, (label, mentions, _))| Keyphrase {
            canonical,
            label,
            mentions,
        })
        .collect()
}

pub(crate) fn searchable_terms(query: &str) -> Vec<String> {
    let stop: BTreeSet<&str> = STOPWORDS.split_whitespace().collect();
    let mut seen = BTreeSet::new();
    query
        .unicode_words()
        .map(str::to_lowercase)
        .filter(|word| {
            word.chars().count() > 1 && !stop.contains(word.as_str()) && seen.insert(word.clone())
        })
        .take(16)
        .collect()
}

const STOPWORDS: &str = "a about above across after again against all also am an and any are around as at be because been before being below between both but by can cannot could did do does doing down during each either else enough especially even ever every few first for from further generally get give given had has have having here how however i if in into is it its itself just keep last least less like made make many may might more most much must my near need neither never next no nor not now of off often on once one only or other our out over own part per perhaps provide provides rather really said same second see several should since so some such than that the their them themselves then there these they thing this those through thus to too under until up upon use used using usually very was we well were what when where whether which while who whom whose why will with within without would yet you your yours arises supplies describes explained explains describes describe located contains include includes including refers called known causes associated";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keyphrase_mentions_point_to_exact_original_text() {
        let text =
            "The Radial Nerve arises from the posterior cord. Radial Nerve supplies the triceps.";
        let phrases = keyphrases(text);
        let radial = phrases
            .iter()
            .find(|p| p.canonical == "radial nerve")
            .unwrap();
        assert_eq!(radial.mentions.len(), 2);
        for (start, end) in &radial.mentions {
            assert_eq!(&text[*start as usize..*end as usize], "Radial Nerve");
        }
        assert!(!phrases.iter().any(|p| p.canonical.contains("from")));
    }

    #[test]
    fn retrieval_terms_do_not_treat_questions_as_fts_operators() {
        assert_eq!(
            searchable_terms("What is the radial nerve OR \"drop\"?"),
            vec!["radial", "nerve", "drop"]
        );
    }
}
