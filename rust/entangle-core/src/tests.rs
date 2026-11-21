use super::*;
use std::io::Write;

fn register(store: &mut Store, directory: &Path, id: &str, text: &str) {
    let path = directory.join(format!("{id}.txt"));
    std::fs::write(&path, text).unwrap();
    register_file(store, &path, id, "text/plain");
}

fn register_file(store: &mut Store, path: &Path, id: &str, media: &str) {
    store
        .register(&Document {
            id: id.into(),
            title: id.into(),
            local_path: path.to_string_lossy().into(),
            media_type: media.into(),
            page_index: 0,
            scroll_offset: 0,
            ingestion_state: "pending".into(),
            indexed_passages: 0,
            error: None,
        })
        .unwrap();
}

fn finish(store: &mut Store, id: &str) {
    loop {
        let progress = store.process_document(id, 8).unwrap();
        assert!(progress.error.is_none(), "{:?}", progress.error);
        if progress.state == "ready" {
            break;
        }
    }
}

#[test]
fn indexing_resumes_after_database_restart_and_pause() {
    let dir = tempfile::tempdir().unwrap();
    let db = dir.path().join("library.sqlite");
    {
        let mut store = Store::open(&db).unwrap();
        register(
            &mut store,
            dir.path(),
            "anatomy",
            &"Radial nerve and posterior cord are connected.\n\n".repeat(40),
        );
        let progress = store.process_document("anatomy", 1).unwrap();
        assert_eq!(progress.processed, 1);
        assert_eq!(progress.total, 40);
        store.set_ingestion_paused("anatomy", true).unwrap();
        assert_eq!(store.process_document("anatomy", 32).unwrap().processed, 1);
        store.save_position("anatomy", 3, 99).unwrap();
    }
    let mut store = Store::open(&db).unwrap();
    assert_eq!(store.documents().unwrap()[0].ingestion_state, "paused");
    store.set_ingestion_paused("anatomy", false).unwrap();
    finish(&mut store, "anatomy");
    assert_eq!(store.passages("anatomy", None).unwrap().len(), 40);
    let source = std::fs::read_to_string(dir.path().join("anatomy.txt")).unwrap();
    for passage in store.passages("anatomy", None).unwrap() {
        assert_eq!(
            &source[passage.start_offset.unwrap() as usize..passage.end_offset.unwrap() as usize],
            passage.text
        );
    }
    assert_eq!(store.documents().unwrap()[0].page_index, 3);
    assert_eq!(store.documents().unwrap()[0].scroll_offset, 99);
}

#[test]
fn concepts_cross_document_references_graph_and_annotations_have_real_evidence() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = Store::open(":memory:").unwrap();
    register(
        &mut store,
        dir.path(),
        "anatomy",
        "The radial nerve arises from the posterior cord. Radial nerve supplies the triceps.",
    );
    register(&mut store, dir.path(), "clinical", "The radial nerve is associated with wrist drop. Radial nerve injury affects wrist extension.");
    finish(&mut store, "anatomy");
    finish(&mut store, "clinical");
    let concept = store
        .concepts("radial nerve", 20)
        .unwrap()
        .into_iter()
        .find(|c| c.id == "c:radial nerve")
        .unwrap();
    let references = store
        .references_for(&concept.id, Some("anatomy"), 20)
        .unwrap();
    assert_eq!(references.len(), 1);
    assert_eq!(references[0].passage.document_id, "clinical");
    assert!(references[0].passage.text.contains("wrist drop"));
    assert!(references[0].passage.page_number.is_none());
    let graph = store.graph(&concept.id).unwrap();
    assert!(!graph.is_empty());
    assert!(graph.iter().all(|e| e.relationship_type == "CO_OCCURS_WITH"
        && e.evidence.text.to_lowercase().contains("radial nerve")));
    assert!(
        store
            .search("What is the radial nerve OR 'MATCH'?", None, 10)
            .unwrap()
            .len()
            >= 2
    );
    let passage = &store.passages("anatomy", None).unwrap()[0];
    store
        .save_annotation(&Annotation {
            id: "note".into(),
            document_id: "anatomy".into(),
            passage_id: Some(passage.id.clone()),
            page_index: 0,
            kind: "note".into(),
            quote: passage.text.clone(),
            note: "Compare clinical explanation".into(),
            color: "yellow".into(),
            created_at: String::new(),
        })
        .unwrap();
    assert_eq!(store.annotations("anatomy").unwrap().len(), 1);
    store.remove_document("anatomy").unwrap();
    assert!(store.search("posterior cord", None, 10).unwrap().is_empty());
    assert!(store.annotations("anatomy").unwrap().is_empty());
    assert!(store
        .graph(&concept.id)
        .unwrap()
        .iter()
        .all(|e| e.evidence.document_id == "clinical"));
    store.retry_document("clinical").unwrap();
    finish(&mut store, "clinical");
    assert_eq!(store.passages("clinical", None).unwrap().len(), 1);
}

#[test]
fn unsupported_and_empty_documents_return_visible_errors() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = Store::open(":memory:").unwrap();
    register(&mut store, dir.path(), "empty", "");
    let progress = store.process_document("empty", 8).unwrap();
    assert_eq!(progress.state, "failed");
    assert!(progress.error.unwrap().contains("No readable text"));
    let path = dir.path().join("unknown.bin");
    std::fs::write(&path, "stuff").unwrap();
    register_file(&mut store, &path, "unknown", "application/octet-stream");
    assert_eq!(
        store.process_document("unknown", 8).unwrap().state,
        "failed"
    );
}

#[test]
fn pause_from_another_connection_during_extraction_is_preserved() {
    let dir = tempfile::tempdir().unwrap();
    let database = dir.path().join("pause.sqlite");
    let mut worker = Store::open(&database).unwrap();
    register(
        &mut worker,
        dir.path(),
        "source",
        &"Radial nerve evidence.\n\n".repeat(3),
    );
    let controller = Store::open(&database).unwrap();
    let progress = worker
        .process_document_with_extractor("source", 32, |document| {
            // This separate connection performs the UI's pause operation while extraction owns no transaction.
            controller.set_ingestion_paused("source", true)?;
            extraction::extract(document)
        })
        .unwrap();
    assert_eq!(progress.state, "paused");
    assert_eq!(progress.processed, 0);
    assert_eq!(progress.total, 3);
    assert!(worker.passages("source", None).unwrap().is_empty());
    controller.set_ingestion_paused("source", false).unwrap();
    finish(&mut worker, "source");
    assert_eq!(worker.passages("source", None).unwrap().len(), 3);
}

#[test]
fn passage_limit_is_visible_and_never_silently_truncates_a_ready_document() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = Store::open(":memory:").unwrap();
    register(
        &mut store,
        dir.path(),
        "oversized",
        &"Evidence.\n\n".repeat(10_001),
    );
    let progress = store.process_document("oversized", 32).unwrap();
    assert_eq!(progress.state, "failed");
    assert!(progress.error.unwrap().contains("10,000-passage"));
    assert_eq!(progress.processed, 0);
    assert_eq!(progress.total, 0);
    assert!(store.passages("oversized", None).unwrap().is_empty());
}

#[test]
fn migration_preserves_foundation_documents_and_unknown_schema_is_rejected() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("old.sqlite");
    let old = Connection::open(&path).unwrap();
    old.execute_batch(include_str!("schema.sql")).unwrap();
    old.execute_batch("INSERT INTO documents(id,title,local_path,media_type) VALUES ('d','Original','/original','text/plain'); INSERT INTO document_editions(id,document_id) VALUES ('d:initial','d'); INSERT INTO reading_positions(document_id,page_index,scroll_offset) VALUES ('d',8,321);").unwrap();
    drop(old);
    let store = Store::open(&path).unwrap();
    assert_eq!(store.status().unwrap().schema_version, 2);
    assert_eq!(store.documents().unwrap()[0].scroll_offset, 321);
    let future = Connection::open(dir.path().join("future.sqlite")).unwrap();
    future.execute_batch("PRAGMA user_version=99;").unwrap();
    assert!(matches!(
        Store::open(dir.path().join("future.sqlite")),
        Err(CoreError::UnsupportedSchema(99))
    ));
}

#[test]
fn mature_parsers_extract_epub_docx_markdown_and_html() {
    let dir = tempfile::tempdir().unwrap();
    let mut store = Store::open(":memory:").unwrap();
    let epub_path = dir.path().join("book.epub");
    {
        let mut zip = zip::ZipWriter::new(std::fs::File::create(&epub_path).unwrap());
        let options = zip::write::SimpleFileOptions::default();
        let files = [
            ("mimetype", "application/epub+zip"),
            ("META-INF/container.xml", "<?xml version='1.0'?><container version='1.0' xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='package.opf' media-type='application/oebps-package+xml'/></rootfiles></container>"),
            ("package.opf", "<?xml version='1.0'?><package version='3.0' unique-identifier='id' xmlns='http://www.idpf.org/2007/opf'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:identifier id='id'>book</dc:identifier><dc:title>Networks</dc:title><dc:language>en</dc:language></metadata><manifest><item id='chapter' href='chapter.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='chapter'/></spine></package>"),
            ("chapter.xhtml", "<html xmlns='http://www.w3.org/1999/xhtml'><head><title>Neural networks</title></head><body><h1>Neural networks</h1><p>A neural network connects artificial neurons through weighted connections.</p></body></html>"),
        ];
        for (name, text) in files {
            zip.start_file(name, options).unwrap();
            zip.write_all(text.as_bytes()).unwrap();
        }
        zip.finish().unwrap();
    }
    register_file(&mut store, &epub_path, "epub", "application/epub+zip");
    finish(&mut store, "epub");
    assert!(store
        .passages("epub", None)
        .unwrap()
        .iter()
        .any(|p| p.text.contains("artificial neurons")));
    assert!(store
        .passages("epub", None)
        .unwrap()
        .iter()
        .all(|p| p.page_number.is_none()));
    let docx_path = dir.path().join("notes.docx");
    docx_rs::Docx::new()
        .add_paragraph(docx_rs::Paragraph::new().add_run(
            docx_rs::Run::new().add_text("Neural network training updates connection weights."),
        ))
        .build()
        .pack(std::fs::File::create(&docx_path).unwrap())
        .unwrap();
    register_file(
        &mut store,
        &docx_path,
        "docx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    );
    finish(&mut store, "docx");
    assert!(
        store.search("connection weights", Some("docx"), 5).unwrap()[0]
            .text
            .contains("training")
    );
    let markdown_path = dir.path().join("notes.md");
    std::fs::write(
        &markdown_path,
        "# Neural networks\n\n**Training** updates connection weights.",
    )
    .unwrap();
    register_file(&mut store, &markdown_path, "markdown", "text/markdown");
    finish(&mut store, "markdown");
    assert!(!store.passages("markdown", None).unwrap()[0]
        .text
        .contains("**"));
    let html_path = dir.path().join("notes.html");
    std::fs::write(&html_path,"<html><head><title>Training</title><script>ignore-this-script</script></head><body><p>Training updates connection weights.</p></body></html>").unwrap();
    register_file(&mut store, &html_path, "html", "text/html");
    finish(&mut store, "html");
    assert!(!store.passages("html", None).unwrap()[0]
        .text
        .contains("ignore-this-script"));
}

#[test]
fn pdf_extraction_keeps_actual_page_numbers() {
    use lopdf::content::{Content, Operation};
    use lopdf::{dictionary, Object, Stream};
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("research.pdf");
    let mut pdf = lopdf::Document::with_version("1.5");
    let pages_id = pdf.new_object_id();
    let font_id =
        pdf.add_object(dictionary! {"Type"=>"Font","Subtype"=>"Type1","BaseFont"=>"Helvetica"});
    let resources_id = pdf.add_object(dictionary! {"Font"=>dictionary!{"F1"=>font_id}});
    let mut kids = Vec::new();
    for text in [
        "Radial nerve supplies triceps",
        "Radial nerve injury causes wrist drop",
    ] {
        let content = Content {
            operations: vec![
                Operation::new("BT", vec![]),
                Operation::new("Tf", vec!["F1".into(), 12.into()]),
                Operation::new("Td", vec![30.into(), 700.into()]),
                Operation::new("Tj", vec![Object::string_literal(text)]),
                Operation::new("ET", vec![]),
            ],
        };
        let content_id = pdf.add_object(Stream::new(dictionary! {}, content.encode().unwrap()));
        let page_id =
            pdf.add_object(dictionary! {"Type"=>"Page","Parent"=>pages_id,"Contents"=>content_id});
        kids.push(page_id.into());
    }
    pdf.objects.insert(pages_id,dictionary!{"Type"=>"Pages","Kids"=>kids,"Count"=>2,"Resources"=>resources_id,"MediaBox"=>vec![0.into(),0.into(),595.into(),842.into()]}.into());
    let catalog_id = pdf.add_object(dictionary! {"Type"=>"Catalog","Pages"=>pages_id});
    pdf.trailer.set("Root", catalog_id);
    pdf.save(&path).unwrap();
    let mut store = Store::open(":memory:").unwrap();
    register_file(&mut store, &path, "pdf", "application/pdf");
    finish(&mut store, "pdf");
    let pages = store.passages("pdf", None).unwrap();
    assert_eq!(pages.len(), 2);
    assert_eq!(pages[1].page_number, Some(2));
    assert!(store.search("wrist drop", Some("pdf"), 5).unwrap()[0]
        .text
        .contains("wrist drop"));
}
