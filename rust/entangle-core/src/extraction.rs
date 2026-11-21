//! Specialized parsers feed bounded passages; source pages are never invented.
use crate::{CoreError, Document};
use docx_rs::{
    DocumentChild, ParagraphChild, RunChild, TableCellContent, TableChild, TableRowChild,
};
use pulldown_cmark::{Event, Parser};
use scraper::{Html, Selector};
use std::fs::File;
use std::io::{BufRead, BufReader};
use std::ops::{Deref, DerefMut};

const MAX_FILE_BYTES: u64 = 64 * 1024 * 1024;
const MAX_EXTRACTED_BYTES: usize = 64 * 1024 * 1024;
const CHUNK_CHARACTERS: usize = 1600;
const MAX_PASSAGES: usize = 10_000;

#[derive(Debug)]
pub(crate) struct ExtractedPassage {
    pub text: String,
    pub section: Option<String>,
    pub page_number: Option<u32>,
    pub start_offset: Option<u32>,
    pub end_offset: Option<u32>,
    pub extraction_method: String,
}

#[derive(Default)]
struct PassageBuffer {
    values: Vec<ExtractedPassage>,
    text_bytes: usize,
}

impl PassageBuffer {
    fn push(&mut self, passage: ExtractedPassage) -> Result<(), CoreError> {
        if self.values.len() >= MAX_PASSAGES {
            return Err(CoreError::Processing(
                "This source exceeds the supported 10,000-passage indexing limit. Split it into smaller documents; the original local file remains available.".into(),
            ));
        }
        let text_bytes = self.text_bytes.saturating_add(passage.text.len());
        if text_bytes > MAX_EXTRACTED_BYTES {
            return Err(CoreError::Processing(
                "Extracted text exceeds the 64 MiB processing budget.".into(),
            ));
        }
        self.text_bytes = text_bytes;
        self.values.push(passage);
        Ok(())
    }
}

impl Deref for PassageBuffer {
    type Target = [ExtractedPassage];
    fn deref(&self) -> &Self::Target {
        &self.values
    }
}

impl DerefMut for PassageBuffer {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.values
    }
}

pub(crate) fn extract(document: &Document) -> Result<Vec<ExtractedPassage>, CoreError> {
    let metadata = std::fs::metadata(&document.local_path)?;
    if metadata.len() > MAX_FILE_BYTES {
        return Err(CoreError::Processing("Indexing currently supports source files up to 64 MiB; the local file remains available to read.".into()));
    }
    let mut passages = PassageBuffer::default();
    match document.media_type.as_str() {
        "application/pdf" => {
            let doc = lopdf::Document::load(&document.local_path).map_err(processing)?;
            if doc.is_encrypted() {
                return Err(CoreError::Processing(
                    "Unlock this PDF before importing it for indexing.".into(),
                ));
            }
            for page in doc.get_pages().keys() {
                let mut text = String::new();
                let mut output = pdf_extract::PlainTextOutput::new(&mut text);
                pdf_extract::output_doc_page(&doc, &mut output, *page).map_err(processing)?;
                append_chunks(
                    &mut passages,
                    &text,
                    Some(format!("Page {page}")),
                    Some(*page),
                    "pdf-extract-0.12.1",
                )?;
            }
        }
        "application/epub+zip" => {
            check_archive(&document.local_path)?;
            let epub = rbook::Epub::open(&document.local_path).map_err(processing)?;
            for (index, result) in epub.reader().enumerate() {
                let data = result.map_err(processing)?;
                let (title, text) = html_text(data.content());
                let section = Some(title.unwrap_or_else(|| format!("Section {}", index + 1)));
                append_chunks(
                    &mut passages,
                    &text,
                    section,
                    None,
                    "rbook-0.7.13/html5ever",
                )?;
            }
        }
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" => {
            check_archive(&document.local_path)?;
            let bytes = std::fs::read(&document.local_path)?;
            let parsed = std::panic::catch_unwind(|| docx_rs::read_docx(&bytes))
                .map_err(|_| {
                    CoreError::Processing(
                        "The DOCX parser rejected malformed document contents.".into(),
                    )
                })?
                .map_err(processing)?;
            let mut section = None;
            for child in &parsed.document.children {
                match child {
                    DocumentChild::Paragraph(paragraph) => {
                        let text = paragraph_text(&paragraph.children);
                        // Heading styles are source metadata, not an inferred page number.
                        if paragraph
                            .property
                            .style
                            .as_ref()
                            .is_some_and(|s| s.val.to_lowercase().starts_with("heading"))
                        {
                            section = Some(text.clone());
                        }
                        append_chunks(
                            &mut passages,
                            &text,
                            section.clone(),
                            None,
                            "docx-rs-0.4.18",
                        )?;
                    }
                    DocumentChild::Table(table) => {
                        let mut text = String::new();
                        table_text(table, &mut text);
                        append_chunks(
                            &mut passages,
                            &text,
                            section.clone(),
                            None,
                            "docx-rs-0.4.18/table",
                        )?;
                    }
                    _ => {}
                }
            }
        }
        "text/html" | "application/xhtml+xml" => {
            let text = std::fs::read_to_string(&document.local_path)?;
            let (title, body) = html_text(&text);
            append_chunks(
                &mut passages,
                &body,
                title,
                None,
                "html5ever/scraper-0.22.0",
            )?;
        }
        "text/markdown" => {
            let input = std::fs::read_to_string(&document.local_path)?;
            let mut text = String::new();
            for event in Parser::new(&input) {
                match event {
                    Event::Text(value) | Event::Code(value) => text.push_str(&value),
                    Event::SoftBreak | Event::HardBreak | Event::End(_) => text.push('\n'),
                    _ => {}
                }
            }
            append_chunks(&mut passages, &text, None, None, "pulldown-cmark-0.13.0")?;
        }
        "text/plain" => {
            let mut buffered = String::new();
            let mut source_offset = 0u32;
            let mut reader = BufReader::new(File::open(&document.local_path)?);
            let mut line = String::new();
            loop {
                line.clear();
                if reader.read_line(&mut line)? == 0 {
                    break;
                }
                buffered.push_str(&line);
                if buffered.chars().count() >= CHUNK_CHARACTERS || line.trim().is_empty() {
                    let first = passages.len();
                    append_chunks(&mut passages, &buffered, None, None, "utf8-text-v1")?;
                    for passage in &mut passages[first..] {
                        passage.start_offset =
                            passage.start_offset.map(|offset| offset + source_offset);
                        passage.end_offset =
                            passage.end_offset.map(|offset| offset + source_offset);
                    }
                    source_offset += buffered.len() as u32;
                    buffered.clear();
                }
            }
            let first = passages.len();
            append_chunks(&mut passages, &buffered, None, None, "utf8-text-v1")?;
            for passage in &mut passages[first..] {
                passage.start_offset = passage.start_offset.map(|offset| offset + source_offset);
                passage.end_offset = passage.end_offset.map(|offset| offset + source_offset);
            }
        }
        _ => {
            return Err(CoreError::Processing(format!(
                "Unsupported indexing media type: {}",
                document.media_type
            )))
        }
    }
    if passages.is_empty() {
        return Err(CoreError::Processing("No readable text was found. Image-only/scanned documents need OCR before knowledge indexing.".into()));
    }
    Ok(passages.values)
}

fn processing(error: impl std::fmt::Display) -> CoreError {
    CoreError::Processing(error.to_string())
}

fn check_archive(path: &str) -> Result<(), CoreError> {
    let mut archive = zip::ZipArchive::new(File::open(path)?).map_err(processing)?;
    let mut total = 0u64;
    if archive.len() > 30_000 {
        return Err(CoreError::Processing(
            "This archive has too many entries to index safely.".into(),
        ));
    }
    for index in 0..archive.len() {
        let entry = archive.by_index(index).map_err(processing)?;
        total = total.saturating_add(entry.size());
        if entry.size() > 32 * 1024 * 1024 || total > 128 * 1024 * 1024 {
            return Err(CoreError::Processing(
                "The expanded archive exceeds this device's 128 MiB indexing budget.".into(),
            ));
        }
    }
    Ok(())
}

fn html_text(input: &str) -> (Option<String>, String) {
    let document = Html::parse_document(input);
    let title_selector = Selector::parse("h1, h2, title").expect("static selector");
    let title = document
        .select(&title_selector)
        .next()
        .map(|element| element.text().collect::<Vec<_>>().join(" "));
    let selector =
        Selector::parse("p, h1, h2, h3, h4, li, td, pre, blockquote").expect("static selector");
    let paragraphs: Vec<String> = document
        .select(&selector)
        .filter_map(|element| {
            // Skip nested selected blocks so table/list descendants are not duplicated.
            let nested = element
                .ancestors()
                .skip(1)
                .filter_map(scraper::ElementRef::wrap)
                .any(|parent| {
                    matches!(
                        parent.value().name(),
                        "p" | "li" | "td" | "pre" | "blockquote"
                    )
                });
            if nested {
                None
            } else {
                Some(element.text().collect::<Vec<_>>().join(" "))
            }
        })
        .collect();
    let text = if paragraphs.is_empty() {
        let body = Selector::parse("body").expect("static selector");
        document
            .select(&body)
            .next()
            .map(|e| e.text().collect::<Vec<_>>().join(" "))
            .unwrap_or_default()
    } else {
        paragraphs.join("\n\n")
    };
    (title.filter(|t| !t.trim().is_empty()), text)
}

fn paragraph_text(children: &[ParagraphChild]) -> String {
    let mut result = String::new();
    for child in children {
        match child {
            ParagraphChild::Run(run) => {
                for value in &run.children {
                    match value {
                        RunChild::Text(text) => result.push_str(&text.text),
                        RunChild::Tab(_) => result.push('\t'),
                        RunChild::Break(_) => result.push('\n'),
                        _ => {}
                    }
                }
            }
            ParagraphChild::Hyperlink(link) => result.push_str(&paragraph_text(&link.children)),
            _ => {}
        }
    }
    result
}

fn table_text(table: &docx_rs::Table, result: &mut String) {
    for TableChild::TableRow(row) in &table.rows {
        for TableRowChild::TableCell(cell) in &row.cells {
            for child in &cell.children {
                match child {
                    TableCellContent::Paragraph(paragraph) => {
                        result.push_str(&paragraph_text(&paragraph.children))
                    }
                    TableCellContent::Table(table) => table_text(table, result),
                    _ => {}
                }
                result.push('\t');
            }
        }
        result.push('\n');
    }
}

fn append_chunks(
    output: &mut PassageBuffer,
    text: &str,
    section: Option<String>,
    page: Option<u32>,
    method: &str,
) -> Result<(), CoreError> {
    if text.len() > MAX_EXTRACTED_BYTES {
        return Err(CoreError::Processing(
            "A source section exceeds the 64 MiB text budget.".into(),
        ));
    }
    let mut start = 0;
    while start < text.len() {
        let remainder = &text[start..];
        let mut end = remainder
            .char_indices()
            .nth(CHUNK_CHARACTERS)
            .map(|(i, _)| start + i)
            .unwrap_or(text.len());
        if end < text.len() {
            if let Some(boundary) = text[start..end].rfind(char::is_whitespace) {
                if boundary > CHUNK_CHARACTERS / 2 {
                    end = start + boundary;
                }
            }
        }
        let segment = &text[start..end];
        let cleaned = segment.trim();
        if !cleaned.is_empty() {
            let leading = segment.len() - segment.trim_start().len();
            output.push(ExtractedPassage {
                text: cleaned.to_owned(),
                section: section.clone(),
                page_number: page,
                // Byte offsets address this extracted page/section, not PDF bytes or UTF-16.
                start_offset: Some((start + leading) as u32),
                end_offset: Some((start + leading + cleaned.len()) as u32),
                extraction_method: method.into(),
            })?;
        }
        start = end;
    }
    Ok(())
}
